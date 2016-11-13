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

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.workspace7.maven.plugins.runners.LaunchRunner;
import org.workspace7.maven.plugins.runners.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This Goal helps in running VertX applications as part of maven build.
 * Pressing <code>Ctrl+C</code> will then terminate the application
 *
 * @since 1.0.0
 */
@Mojo(name = "run", threadSafe = true,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class RunMojo extends AbstractVertxMojo {

    /* ==== Vertx Program Args ==== */

    public static final String VERTX_ARG_RUN = "run";
    public static final String VERTX_ARG_LAUNCHER_CLASS = "--launcher-class";
    public static final String VERTX_ARG_REDEPLOY = "--redeploy";
    public static final String VERTX_REDEPLOY_DEFAULT_PATTERN = "src/**/*.java";
    public static final String VERTX_ARG_CONF = "-conf";

    /* ==== Maven related ==== */

    /**
     * The maven project classes directory, defaults to target/classes
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;

    /**
     * This property is used to enable vertx to do redeployment of the verticles in case of modifications
     * to the sources.
     * The redeployPattern defines the source directories that will be watched for changes to trigger redeployment
     */
    @Parameter(name = "redeploy", defaultValue = "false")
    protected boolean redeploy;

    /**
     * The redeployPatterns that will be used to trigger redeployment of the vertx application.
     * If this this not provided and redeploy is &quot;true&quot; then default value of "src/**.*.java" will be
     * used as a redeployment pattern
     */
    @Parameter(name = "redeployPatterns")
    protected List<String> redeployPatterns;

    /**
     * The additional arguments that will be passed as program arguments to the JVM, all standard vertx arguments are
     * automatically applied
     */
    @Parameter(alias = "runArgs", property = "vertx.jvmArguments")
    protected String[] runArgs;

    /**
     * The flag to indicate whether to run the vertx application in forked mode or within running maven jvm.
     * By default its run under maven JVM
     */
    @Parameter(property = "fork")
    protected boolean forked;

    /**
     * This property will be passed as the -conf option to vertx run. It defaults to file
     * "src/main/conf/${project.artifactId}.conf", if it exists it will passed to the vertx run
     */
    @Parameter(alias = "conf", property = "vertx.conf", defaultValue = "src/main/conf/${project.artifactId}.conf")
    File conf;

    /**
     * This property will be used as the working directory for the process when running in forked mode.
     * This defaults to ${project.basedir}
     */
    @Parameter(alias = "workDirectory", property = "vertx.workdirectory", defaultValue = "${project.basedir}")
    File workDirectory;

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
            addClasspath(argsList);
            addVertxArgs(argsList);

            int exitCode = runAsForked(argsList);
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
        LaunchRunner.IsolatedThreadGroup threadGroup = new LaunchRunner.IsolatedThreadGroup(launcher, getLog());
        Thread launcherThread = new Thread(threadGroup, new LaunchRunner(launcher, argsList));
        launcherThread.setContextClassLoader(buildClassLoader(getClassPathUrls()));
        launcherThread.start();
        LaunchRunner.join(threadGroup);
        threadGroup.rethrowException();
    }


    protected int runAsForked(List<String> argsList) throws MojoExecutionException {
        ProcessRunner processRunner = new ProcessRunner(getLog(), this.workDirectory, argsList);
        int exitCode = processRunner.run();
        return exitCode;
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

        //Since non forked mode will be using the IO_VERTX_CORE_LAUNCHER, we don't need to pass it as args
        if (isFork()) {
            argsList.add(IO_VERTX_CORE_LAUNCHER);
        }

        argsList.add(VERTX_ARG_RUN);
        argsList.add(verticle);

        argsList.add(VERTX_ARG_LAUNCHER_CLASS);
        argsList.add(launcher);

        if (redeploy) {
            getLog().info("VertX application redeploy enabled");
            argsList.add(VERTX_ARG_REDEPLOY);
            if (redeployPatterns != null && redeployPatterns.isEmpty()) {
                argsList.addAll(redeployPatterns);
            } else {
                argsList.add(VERTX_REDEPLOY_DEFAULT_PATTERN);
            }
        }

        if (conf != null && conf.exists() && conf.isFile()) {
            getLog().info("Using configuration from file: " + conf.toString());
            argsList.add(VERTX_ARG_CONF);
            argsList.add(conf.toString());
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

    private boolean isFork() {
        return Boolean.TRUE.equals(forked);
    }

}
