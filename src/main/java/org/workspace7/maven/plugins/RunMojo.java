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

import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.workspace7.maven.plugins.utils.LaunchRunner;
import org.workspace7.maven.plugins.utils.SignalListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author kameshs
 */
@Mojo(name = "run", threadSafe = true, requiresDependencyCollection = ResolutionScope.TEST)
public class RunMojo extends AbstractVertxMojo {

    private static final Method INHERIT_IO_METHOD = MethodUtils.getAccessibleMethod(ProcessBuilder.class, "inheritIO");

    /* ==== Maven realated ==== */

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;

    @Parameter(name = "redeploy", defaultValue = "false")
    protected boolean redeploy;

    @Parameter(name = "redeployPatterns")
    protected List<String> redeployPatterns;

    @Parameter(name = "run.arguments")
    protected String[] runArgs;

    @Parameter(property = "fork")
    protected boolean forked;

    /* ==== Application related  ==== */
    Process process;

    long endTime;

    /**
     * There's a bug in the Windows VM (https://bugs.openjdk.java.net/browse/JDK-8023130)
     * that means we need to avoid inheritIO
     * Thanks to SpringBoot Maven Plugin(https://github.com/spring-projects/spring-boot/blob/master/spring-boot-tools/spring-boot-maven-plugin)
     * for showing way to handle this
     */
    private static boolean isInheritIOBroken() {
        if (!System.getProperty("os.name", "none").toLowerCase().contains("windows")) {
            return false;
        }
        String runtime = System.getProperty("java.runtime.version");
        if (!runtime.startsWith("1.7")) {
            return false;
        }
        String[] tokens = runtime.split("_");
        if (tokens.length < 2) {
            return true;
        }
        try {
            Integer build = Integer.valueOf(tokens[1].split("[^0-9]")[0]);
            if (build < 60) {
                return true;
            }
        } catch (Exception ex) {
            return true;
        }
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        List<String> argsList = new ArrayList<>();

        if (getLog().isDebugEnabled()) {

            StringBuilder commandBuilder = new StringBuilder();

            argsList.forEach(s -> {
                commandBuilder.append(s);
                commandBuilder.append(" ");
            });

            getLog().debug("Running command : " + commandBuilder.toString());
        }

        if (isFork()) {
            getLog().info("Running in forked mode");
            argsList.add("java");
            addClasspath(argsList);
            addVertxArgs(argsList);

            int exitCode = runAsForked(true, argsList);
            if (exitCode == 0) {
                return;
            }
        } else {
            getLog().info("Running with fork disabled");
            addVertxArgs(argsList);
            runInMavenJvm(argsList);
        }

    }

    protected void runInMavenJvm(List<String> argsList) throws MojoExecutionException {
        IsolatedThreadGroup threadGroup = new IsolatedThreadGroup(launcher);
        Thread launcherThread = new Thread(threadGroup, new LaunchRunner(launcher, argsList));
        launcherThread.setContextClassLoader(buildClassLoader(getClassPathUrls()));
        launcherThread.start();
        join(threadGroup);
        threadGroup.rethrowException();
    }


    protected int runAsForked(boolean waitFor, List<String> argsList) throws MojoExecutionException {

        try {

            ProcessBuilder vertxRunProcBuilder = new ProcessBuilder("java");
            vertxRunProcBuilder.command(argsList);
            vertxRunProcBuilder.redirectErrorStream(true);
            boolean inheritedIO = inheritIO(vertxRunProcBuilder);

            Process vertxRunProc = vertxRunProcBuilder.start();
            this.process = vertxRunProc;
            //Attach Shutdown Hook
            Runtime.getRuntime().addShutdownHook(new Thread(new RunProcessKiller()));

            if (!inheritedIO) {
                redirectOutput(vertxRunProc);
            }

            SignalListener.handle(() -> handleSigInt());

            if (waitFor) {
                return this.process.waitFor();
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Error running command :", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Unable to run command:", e);
        } finally {
            if (waitFor) {
                this.endTime = System.currentTimeMillis();
                this.process = null;
            }
        }

        return 7;
    }


    private void addClasspath(List<String> args) throws MojoExecutionException {
        try {
            StringBuilder classpath = new StringBuilder();
            for (URL ele : getClassPathUrls()) {
                classpath = classpath
                        .append((classpath.length() > 0 ? File.pathSeparator : "")
                                + new File(ele.toURI()));
            }
            getLog().debug("Classpath for forked process: " + classpath);
            args.add("-cp");
            args.add(classpath.toString());
        } catch (Exception ex) {
            throw new MojoExecutionException("Could not build classpath", ex);
        }
    }

    private void addClassesDirectory(List<URL> classpathUrls) throws IOException {

        classpathUrls.add(this.classesDirectory.toURI().toURL());
    }

    private void addProjectResources(List<URL> classpathUrls) throws IOException {

        for (Resource resource : this.project.getResources()) {
            File f = new File(resource.getDirectory());
            classpathUrls.add(f.toURI().toURL());
        }
    }

    private void addVertxArgs(List<String> argsList) {

        Objects.requireNonNull(launcher);

        //Since non forked mode will be using the IO_VERTX_CORE_LAUNCHER, we dont need to pass it as args
        if (isFork()) {
            argsList.add(IO_VERTX_CORE_LAUNCHER);
        }

        argsList.add("run");
        argsList.add(verticle);

        argsList.add("--launcher-class");
        argsList.add(launcher);

        //TODO - need to get this patterns
        if (redeploy) {
            argsList.add("--redeploy");
            if (redeployPatterns != null && redeployPatterns.isEmpty()) {
                argsList.addAll(redeployPatterns);
            } else {
                argsList.add("src/**/*.java");
            }
        }
    }

    private ClassLoader buildClassLoader(Collection<URL> classPathUrls) throws MojoExecutionException {
        return new URLClassLoader(classPathUrls.toArray(new URL[classPathUrls.size()]));
    }

    private List<URL> getClassPathUrls() throws MojoExecutionException {
        List<URL> classPathUrls = new ArrayList<>();

        try {
            addProjectResources(classPathUrls);
            addClassesDirectory(classPathUrls);

            Set<Optional<File>> compileAndRuntimeDeps = extractArtifactPaths(this.project.getDependencyArtifacts());

            Set<Optional<File>> transitiveDeps = extractArtifactPaths(this.project.getArtifacts());

            classPathUrls.addAll(Stream.concat(compileAndRuntimeDeps.stream(), transitiveDeps.stream())
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

        } catch (IOException e) {
            throw new MojoExecutionException("Unable to run:", e);
        }
        return classPathUrls;
    }

    private void doKill() {
        try {
            this.process.destroy();
            this.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleSigInt() {
        boolean justEnded = System.currentTimeMillis() < (this.endTime + 500);
        if (!justEnded) {
            //Kill it
            doKill();
        }
    }

    private boolean isFork() {
        return Boolean.TRUE.equals(forked);
    }

    private boolean inheritIO(ProcessBuilder processBuilder) {

        if (isInheritIOBroken()) {
            return false;
        }

        try {
            INHERIT_IO_METHOD.invoke(processBuilder);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private void join(IsolatedThreadGroup threadGroup) {
        boolean hasNoDaemonThreads;
        do {
            hasNoDaemonThreads = false;
            Thread[] threads = new Thread[threadGroup.activeCount()];
            threadGroup.enumerate(threads);
            for (Thread thread : threads) {
                if (thread != null && !thread.isDaemon()) {
                    try {
                        hasNoDaemonThreads = true;
                        thread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } while (hasNoDaemonThreads);
    }

    private void redirectOutput(Process process) {
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
        new Thread() {
            @Override
            public void run() {
                try {
                    String line = reader.readLine();
                    while (line != null) {
                        System.out.println(line);
                        line = reader.readLine();
                        System.out.flush();
                    }
                    reader.close();
                } catch (IOException e) {
                    //Ignore
                }
            }
        }.start();
    }

    /**
     * Isolated ThreadGroup to catch uncaught exceptions {@link ThreadGroup}
     */
    final class IsolatedThreadGroup extends ThreadGroup {

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

    final class RunProcessKiller implements Runnable {

        @Override
        public void run() {
            doKill();
        }

    }
}
