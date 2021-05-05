package org.intellij.sonar.analysis;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.PsiFile;
import git4idea.repo.GitRepositoryManager;
import org.intellij.sonar.console.SonarConsole;
import org.intellij.sonar.index.IssuesByFileIndexer;
import org.intellij.sonar.index.SonarIssue;
import org.intellij.sonar.persistence.*;
import org.intellij.sonar.sonarserver.SonarServer;
import org.intellij.sonar.util.DurationUtil;
import org.sonarqube.ws.Issues.Issue;

import java.util.*;
import java.util.stream.Collectors;

public class DownloadIssuesTask implements Runnable {

  private final SonarServerConfig sonarServerConfig;
  private final Set<String> resourceKeys;
  private final List<PsiFile> psiFiles;
  private final Map<String,ImmutableList<Issue>> downloadedIssuesByResourceKey = Maps.newConcurrentMap();
  private final SonarQubeInspectionContext.EnrichedSettings enrichedSettings;
  private final SonarConsole sonarConsole;

  private DownloadIssuesTask(Project project,
                             SonarQubeInspectionContext.EnrichedSettings enrichedSettings,
                             SonarServerConfig sonarServerConfig,
                             Set<String> resourceKeys,
                             List<PsiFile> psiFiles) {
    this.enrichedSettings = enrichedSettings;
    this.sonarServerConfig = sonarServerConfig;
    this.resourceKeys = resourceKeys;
    this.psiFiles = psiFiles;
    this.sonarConsole = SonarConsole.get(project);
  }

  public static Optional<DownloadIssuesTask> from(Project project,
                                                  SonarQubeInspectionContext.EnrichedSettings enrichedSettings,
                                                  ImmutableList<PsiFile> psiFiles) {
    return new DownloadIssuesTaskBuilder().buildFrom(project, enrichedSettings, psiFiles).maybeGetDownloadIssuesTask();
  }

  @Override
  public void run() {
    final SonarServer sonarServer = SonarServer.create(sonarServerConfig);
    final long startTime = System.currentTimeMillis();
    inspectBranchNames();
    for (String resourceKey : resourceKeys) {
      final String branchName = getBranchName(resourceKey);
      final String downloadingIssuesMessage = String.format("Downloading issues for SonarQube resource %s[%s]",resourceKey, branchName);
      sonarConsole.info(downloadingIssuesMessage);
        tryDownloadingIssues(sonarServer, resourceKey, branchName);
    }
    onSuccess(startTime);
  }

  private void inspectBranchNames(){
      Module m1 = enrichedSettings.module;
      if (m1 != null) {
          sonarConsole.info("M1: " + m1.getName());
          sonarConsole.info("M1: " + m1.getModuleFilePath());
      }
      Project p = enrichedSettings.project;
      sonarConsole.info("P: " + p.getName());
      sonarConsole.info("P: " + p.getBasePath());

      for(Module m : ModuleManager.getInstance(p).getModules()){
          sonarConsole.info("M: " + m.getName());
          sonarConsole.info("M: " + m.getModuleFilePath());
          var repo = GitRepositoryManager.getInstance(p).getRepositoryForFile(m.getModuleFile());
          if (repo != null) {
              sonarConsole.info("MR: " + repo.getCurrentBranchName());
          }
      }
      var repos = GitRepositoryManager.getInstance(p).getRepositories();
      for(var repo : repos) {
          sonarConsole.info("BA: " + repo.getCurrentBranchName());
          sonarConsole.info("BB: " + repo.getVcs().getName());
          sonarConsole.info("BC: " + repo.getVcs().getDisplayName());
          sonarConsole.info("BC: " + repo.getVcs().getShortName());
          sonarConsole.info("BD: " + repo.getProject().getName());
          sonarConsole.info("BE: " + repo.getProject().getProjectFile().getName());
          sonarConsole.info("BF: " + repo.getProject().getProjectFile().getPath());
          sonarConsole.info("BG: " + repo.getProject().getProjectFile().getUrl());
          sonarConsole.info("BH: " + repo.getProject().getProjectFile().getPresentableName());
          sonarConsole.info("BI: " + repo.getProject().getProjectFile().getPresentableUrl());
      }
  }

  private String getBranchName(String projectKey){
      String projectName = projectKey.substring(projectKey.indexOf(":")+1);
      sonarConsole.info("Looking for current branch in [" + projectName + "]");

      Project p = enrichedSettings.project;

      var associatedModule = Arrays.stream(ModuleManager.getInstance(p).getModules()).filter(module -> projectName.equals(module.getName())).findFirst().get();
      sonarConsole.info("Found module: [" + associatedModule.getName() + "]");
      var repoForModule = GitRepositoryManager.getInstance(p).getRepositoryForFile(associatedModule.getModuleFile());
      if (repoForModule != null) {
        var foundBranch = repoForModule.getCurrentBranchName();
        sonarConsole.info("Found branch: [" + foundBranch + "]");
        return foundBranch;
      }

      return "";
  }

    private void tryDownloadingIssues(SonarServer sonarServer, String resourceKey, String branchName) {
        ImmutableList<Issue> issues;
        try {
            issues = sonarServer.getAllIssuesFor(resourceKey, sonarServerConfig.getOrganization(), branchName);
            downloadedIssuesByResourceKey.put(resourceKey,issues);
        } catch (Exception e) {
            sonarConsole.error(e.getMessage());
            Notifications.Bus.notify(
                    new Notification(
                            "SonarQube","SonarQube",
                            "Downloading sonar issues failed!", NotificationType.ERROR
                    ),enrichedSettings.project
            );
        }
    }

    private void onSuccess(long downloadStartTime) {
    final long downloadedIssuesCount = downloadedIssuesByResourceKey.values().stream()
            .mapToLong(AbstractCollection::size)
            .sum();
    sonarConsole.info(
      String.format(
        "Downloaded %d issues in %s",
        downloadedIssuesCount,
        DurationUtil.getDurationBreakdown(System.currentTimeMillis()-downloadStartTime)
      )
    );
    createIssuesIndex();
  }

  private void createIssuesIndex() {
    for (Map.Entry<String, ImmutableList<Issue>> entry : downloadedIssuesByResourceKey.entrySet()) {
      if (ProgressManager.getInstance().getProgressIndicator().isCanceled()) break;
      sonarConsole.info(String.format("Creating index for SonarQube resource %s",entry.getKey()));
      long indexCreationStartTime = System.currentTimeMillis();
      final ImmutableList<Issue> issues = entry.getValue();
      final Map<String, Set<SonarIssue>> index = new IssuesByFileIndexer(psiFiles)
        .withSonarServerIssues(issues)
        .withSonarConsole(sonarConsole)
        .create();
      final Optional<IssuesByFileIndexProjectService> indexService =
        IssuesByFileIndexProjectService.getInstance(enrichedSettings.project);
      indexService.ifPresent(s -> s.getIndex().putAll(index));
      final int issuesCountInIndex = (int) index.values().stream()
              .mapToLong(Set::size)
              .sum();
      sonarConsole.info(
        String.format(
          "Finished creating index with %d issues for SonarQube resource %s in %s",
          issuesCountInIndex,
          entry.getKey(),
          DurationUtil.getDurationBreakdown(System.currentTimeMillis()-indexCreationStartTime)
        )
      );
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(DownloadIssuesTask.class.getName())
      .add("sonarServerConfig",sonarServerConfig)
      .add("resourceKeys",resourceKeys)
      .add("psiFiles",psiFiles)
      .add("downloadedIssuesByResourceKey",downloadedIssuesByResourceKey)
      .toString();
  }

    private static class DownloadIssuesTaskBuilder {
        private DownloadIssuesTask downloadIssuesTask;
        private boolean processing;
        private String serverName;
        private Settings settings;
        private SonarServerConfig sonarServerConfig;

        DownloadIssuesTaskBuilder buildFrom(
            Project project,
                SonarQubeInspectionContext.EnrichedSettings enrichedSettings,
                List<PsiFile> psiFiles) {
            downloadIssuesTask=null;
            processing = true;
            checkNotNull(enrichedSettings);
            if (processing) initSettings(enrichedSettings);
            if (processing) checkNotNullServerName();
            if (processing) initSonarServerConfig();
            if (processing) buildDownloadIssuesTask(project, enrichedSettings, psiFiles);
            return this;
        }

        private void checkNotNull(SonarQubeInspectionContext.EnrichedSettings enrichedSettings) {
            if (enrichedSettings.settings == null) {
                processing = false;
            }
        }

        private void initSettings(SonarQubeInspectionContext.EnrichedSettings enrichedSettings) {
            settings = enrichedSettings.settings.enrichWithProjectSettings(enrichedSettings.project);
        }

        private void checkNotNullServerName() {
            serverName = settings.getServerName();
            if (serverName == null) {
                processing = false;
            }
        }

        private void initSonarServerConfig() {
            final Optional<SonarServerConfig> maybeSonarServerConfig = SonarServers.get(serverName);
            if (!maybeSonarServerConfig.isPresent()) {
                processing = false;
            } else {
                sonarServerConfig = maybeSonarServerConfig.get();
            }
        }

        private void buildDownloadIssuesTask(Project project, SonarQubeInspectionContext.EnrichedSettings enrichedSettings, List<PsiFile> psiFiles) {
            final Set<String> resourceKeys = settings.getResources().stream().map(Resource::getKey).collect(Collectors.toSet());
            downloadIssuesTask = new DownloadIssuesTask(project, enrichedSettings, sonarServerConfig, resourceKeys, psiFiles);
        }

        Optional<DownloadIssuesTask> maybeGetDownloadIssuesTask() {
            return Optional.ofNullable(downloadIssuesTask);
        }
    }
}
