/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.appservice.commands;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.microsoft.azure.management.appservice.PublishingProfile;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitTool;
import hudson.remoting.VirtualChannel;
import org.apache.commons.io.FilenameUtils;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;

public class GitDeployCommand implements ICommand<GitDeployCommand.IGitDeployCommandData> {

    private static final String DEPLOY_REPO = ".azure-deploy";
    private static final String DEPLOY_COMMIT_MESSAGE = "Deploy ${BUILD_TAG}";
    private static final String DEPLOY_BRANCH = "master";
    private static final String DEPLOY_REMOTE_BRANCH = "origin/" + DEPLOY_BRANCH;

    @Override
    public void execute(IGitDeployCommandData context) {
        try {
            final PublishingProfile pubProfile = context.getPublishingProfile();
            final AbstractBuild<?, ?> build = context.getBuild();
            final TaskListener listener = context.getListener();
            final EnvVars env = build.getEnvironment(listener);
            final FilePath ws = build.getWorkspace();
            final FilePath repo = ws.child(DEPLOY_REPO);
            final String gitExe = getGitExe(env);

            GitClient git = Git.with(listener, env)
                .in(repo)
                .using(gitExe)
                .getClient();

            git.addCredentials(pubProfile.gitUrl(), new UsernamePasswordCredentialsImpl(
                    CredentialsScope.SYSTEM, "", "", pubProfile.gitUsername(), pubProfile.gitPassword()));

            git.clone_().url(pubProfile.gitUrl()).execute();

            // Sometimes remote repository is bare and the master branch doesn't exist
            Set<Branch> branches = git.getRemoteBranches();
            for (Branch branch : branches) {
                if (branch.getName().equals(DEPLOY_REMOTE_BRANCH)) {
                    git.checkout().ref(DEPLOY_BRANCH).execute();
                    break;
                }
            }

            cleanWorkingDirectory(git);

            copyAndAddFiles(git, ws, repo, context.getFilePath(), Util.fixNull(context.getTargetDirectory()));

            if (!isWorkingTreeChanged(git)) {
                context.logStatus("Deploy repository is up-to-date. Nothing to commit.");
                context.setDeploymentState(DeploymentState.Success);
                return;
            }

            git.commit(env.expand(DEPLOY_COMMIT_MESSAGE));

            git.push().to(new URIish(pubProfile.gitUrl())).execute();

            context.setDeploymentState(DeploymentState.Success);

        } catch (IOException | InterruptedException | URISyntaxException e) {
            e.printStackTrace();
            context.logError("Fail to deploy using Git: " + e.getMessage());
            context.setDeploymentState(DeploymentState.HasError);
        }
    }


    private String getGitExe(EnvVars env) {
        GitTool tool = GitTool.getDefaultInstallation();
        if (env != null) {
            tool = tool.forEnvironment(env);
        }
        return tool.getGitExe();
    }

    /**
     * Remove all existing files in the working directory, from both git and disk
     *
     * This method is modified from RmCommand in JGit.
     * @param git Git client
     * @throws IOException
     * @throws InterruptedException
     */
    private void cleanWorkingDirectory(GitClient git) throws IOException, InterruptedException {
        git.withRepository(new RepositoryCallback<Void>() {
            @Override
            public Void invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
                DirCache dc = null;

                try (final TreeWalk tw = new TreeWalk(repo)) {
                    dc = repo.lockDirCache();
                    DirCacheBuilder builder = dc.builder();
                    tw.reset(); // drop the first empty tree, which we do not need here
                    tw.setRecursive(true);
                    tw.setFilter(TreeFilter.ALL);
                    tw.addTree(new DirCacheBuildIterator(builder));

                    while (tw.next()) {
                        final FileMode mode = tw.getFileMode(0);
                        if (mode.getObjectType() == Constants.OBJ_BLOB) {
                            final File path = new File(repo.getWorkTree(),
                                    tw.getPathString());
                            // Deleting a blob is simply a matter of removing
                            // the file or symlink named by the tree entry.
                            delete(repo, path);
                        }
                    }
                    builder.commit();
                } finally {
                    if (dc != null)
                        dc.unlock();
                }

                return null;
            }

            private void delete(Repository repo, File p) {
                while (p != null && !p.equals(repo.getWorkTree()) && p.delete())
                    p = p.getParentFile();
            }
        });
    }

    /**
     * Copy selected files to git working directory and stage them
     *
     * @param git Git client
     * @param ws Path to workspace
     * @param repo Path to git repo
     * @param filesPattern Files name pattern
     * @param targetDir Target directory
     * @throws IOException
     * @throws InterruptedException
     */
    private void copyAndAddFiles(GitClient git, FilePath ws, FilePath repo, String filesPattern, String targetDir)
            throws IOException, InterruptedException {
        FileSet fs = Util.createFileSet(new File(ws.getRemote()), filesPattern);
        DirectoryScanner ds = fs.getDirectoryScanner();
        String[] files = ds.getIncludedFiles();
        for (String file: files) {
            FilePath srcPath = new FilePath(ws, file);
            FilePath repoPath = new FilePath(repo.child(targetDir), file);
            srcPath.copyTo(repoPath);

            // Git always use Unix file path
            String filePathInGit = FilenameUtils.separatorsToUnix(FilenameUtils.concat(targetDir, file));
            git.add(filePathInGit);
        }
    }

    /**
     * Check if working tree changed
     *
     * @param git Git client
     * @return If working tree changed.
     * @throws IOException
     * @throws InterruptedException
     */
    private boolean isWorkingTreeChanged(GitClient git) throws IOException, InterruptedException {
        return git.withRepository(new RepositoryCallback<Boolean>() {
            @Override
            public Boolean invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
                FileTreeIterator workingTreeIt = new FileTreeIterator(repo);
                IndexDiff diff = new IndexDiff(repo, Constants.HEAD, workingTreeIt);
                return diff.diff();
            }
        });
    }

    public interface IGitDeployCommandData extends IBaseCommandData {

        PublishingProfile getPublishingProfile();

        String getFilePath();

        String getTargetDirectory();
    }
}