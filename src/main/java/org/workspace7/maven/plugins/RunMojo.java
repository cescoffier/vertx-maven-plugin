/*
 *   Copyright 2016 Kamesh Sampath
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.workspace7.maven.plugins;

import io.vertx.core.Launcher;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.workspace7.maven.plugins.utils.ThreadUtil;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author kameshs
 */
@Mojo(name = "run", threadSafe = true, requiresDependencyCollection = ResolutionScope.TEST)
public class RunMojo extends AbstractVertxMojo {

    @Parameter(name = "redeploy", defaultValue = "false")
    protected boolean redeploy;

    @Parameter(name = "redeployPattern")
    protected List<String> redeployPatterns;

    @Parameter(name = "run.arguments")
    protected String[] runArgs;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (this.project.getAttachedArtifacts().isEmpty()) {
            throw new MojoFailureException("Unable to find " + vertxArtifactName() + "  run vertx-maven-plugin:package");
        }

        //Move this as parameter
        List<String> argsList = new ArrayList<>();
        argsList.add("run");
        argsList.add(verticle);
        //TODO - need to get this patterns
        if (redeploy) {
            argsList.add("--redeploy");
            if (redeployPatterns != null && redeployPatterns.isEmpty()) {
                argsList.addAll(redeployPatterns);
            } else {
                argsList.add("src/**/*.java");
            }
        }

        if (launcher != null) {


            argsList.add("--launcher-class");
            argsList.add(launcher);

            final Launcher vertxLauncher = new Launcher();

            ThreadGroup isolatedThreadGroup = new IsolatedThreadGroup(launcher);

            Thread vertxBootstrapThread = new Thread(isolatedThreadGroup, new Runnable() {
                @Override
                public void run() {
                    if (IO_VERTX_CORE_LAUNCHER.equals(launcher)) {
                        String[] args = new String[argsList.size()];
                        args = argsList.toArray(args);
                        vertxLauncher.dispatch(args);

                    } else {
                        try {
                            Class.forName(launcher).newInstance();
                            String[] args = new String[argsList.size()];
                            args = argsList.toArray(args);
                            vertxLauncher.dispatch(args);
                        } catch (InstantiationException e) {
                            getLog().error("Error running " + e.getMessage(), e);
                        } catch (IllegalAccessException e) {
                            getLog().error("Error running " + e.getMessage(), e);
                        } catch (ClassNotFoundException e) {
                            getLog().error("Error running " + e.getMessage(), e);
                        }
                    }
                }
            });

            vertxBootstrapThread.setContextClassLoader(getClassLoader());
            vertxBootstrapThread.start();


            ThreadUtil threadUtil = new ThreadUtil().build(getLog());

            threadUtil.joinNonDaemonThread(isolatedThreadGroup);

        }

    }


    private ClassLoader getClassLoader() throws MojoExecutionException {
        List<URL> classPathURLs = new ArrayList<>();


        Set<Optional<File>> compileAndRuntimeDeps = extractArtifactPaths(this.project.getDependencyArtifacts());

        Set<Optional<File>> transitiveDeps = extractArtifactPaths(this.project.getArtifacts());

        classPathURLs.addAll(Stream.concat(compileAndRuntimeDeps.stream(), transitiveDeps.stream())
                .filter(file -> file.isPresent())
                .map(file -> {
                    try {
                        return file.get().toURI().toURL();
                    } catch (Exception e) {
                        getLog().error("Error building classpath", e);
                    }
                    return null;
                })
                .filter(url -> url != null)
                .collect(Collectors.toList()));
        //TODO - Do we need plugin dependnecies as well ?


        return new URLClassLoader(classPathURLs.toArray(new URL[classPathURLs.size()]));
    }

    /**
     * Isolated ThreadGroup to catch uncaught exceptions {@link ThreadGroup}
     */
    class IsolatedThreadGroup extends ThreadGroup {

        private Object monitor = new Object();
        private Throwable exception;

        public IsolatedThreadGroup(String name) {
            super(name);
        }

        public void rethrowException() throws MojoExecutionException {
            synchronized (this.monitor) {
                if (this.exception != null) {
                    throw new MojoExecutionException("Error occurred while running.." +
                            this.exception.getMessage(), this.exception);
                }
            }
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (!(e instanceof ThreadDeath)) {
                synchronized (this.monitor) {
                    this.exception = (this.exception != null ? e : this.exception);
                }
                getLog().warn(e);
            }
        }
    }
}
