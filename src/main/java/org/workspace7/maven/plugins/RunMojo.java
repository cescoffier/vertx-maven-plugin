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

import org.apache.commons.lang3.ClassUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.workspace7.maven.plugins.runners.LaunchRunner;
import org.workspace7.maven.plugins.runners.ProcessRunner;
import org.workspace7.maven.plugins.utils.ConfigConverterUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * The default command to use when calling io.vertx.core.Launcher.
     * possible commands are,
     * <ul>
     * <li>bare</li>
     * <li>list</li>
     * <li>run</li>
     * <li>start</li>
     * <li>stop</li>
     * <li>run</li>
     * </ul>
     */
    protected String vertxCommand = "run";
    /**
     * This property will be passed as the -conf option to vertx run. It defaults to file
     * "src/main/conf/${project.artifactId}.conf", if it exists it will passed to the vertx run
     */
    @Parameter(alias = "conf", property = "vertx.conf", defaultValue = "src/main/conf/${project.artifactId}.json")
    File conf;
    /**
     * This property will be used as the working directory for the process when running in forked mode.
     * This defaults to ${project.basedir}
     */
    @Parameter(alias = "workDirectory", property = "vertx.workdirectory", defaultValue = "${project.basedir}")
    File workDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        //FIXME - open it up in respective commit
        //scanAndLoadConfigs();
        List<String> argsList = new ArrayList<>();

        if (getLog().isDebugEnabled()) {

            StringBuilder commandBuilder = new StringBuilder();

            argsList.forEach(s -> {
                commandBuilder.append(s);
                commandBuilder.append(" ");
            });

            getLog().debug("Running command : " + commandBuilder.toString());
        }

        boolean isVertxLauncher = isVertxLauncher(launcher);

        if (forked) {
            getLog().info("Running in forked mode");
            addClasspath(argsList);
            if (isVertxLauncher) {
                addVertxArgs(argsList);
            } else {
                argsList.add(launcher);
            }

            int exitCode = runAsForked(argsList);
            if (exitCode == 0) {
                return;
            }
        } else {
            getLog().info("Running with fork disabled");
            if (isVertxLauncher) {
                addVertxArgs(argsList);
            } else {
                argsList.add(launcher);
            }
            runInMavenJvm(argsList);
        }

    }

    /**
     * This method to load Vert.X application configurations.
     * This will use the pattern ${basedir}/src/main/conf/artifactId.[json/yaml]
     */
    protected void scanAndLoadConfigs() throws MojoExecutionException {
        String artifactId = this.project.getArtifactId();
        //Check if its JSON
        Path confPath = Paths.get(this.project.getBasedir().toString(), "src/main/conf", artifactId, ".json");
        if (confPath != null && confPath.toFile().exists() && confPath.toFile().isFile()) {
            conf = confPath.toFile();
            return;
        }

        //Check if its YAML
        confPath = Paths.get(this.project.getBasedir().toString(), "src/main/conf", artifactId, ".yaml");
        Path jsonConfPath = Paths.get(this.projectBuildDir, "conf", artifactId, ".json");
        if (confPath != null && confPath.toFile().exists() && confPath.toFile().isFile()) {
            try {
                ConfigConverterUtil.convertYamlToJson(confPath, jsonConfPath);
                conf = confPath.toFile();
            } catch (IOException e) {
                throw new MojoExecutionException("Error loading configuration file:" + confPath.toString());
            }
        }

    }

    /**
     * This method will trigger the lauch of the applicaiton as non-forked, running in same JVM as maven.
     *
     * @param argsList - the arguments to be passed to the vertx launcher
     * @throws MojoExecutionException - any error that might occur while starting the process
     */

    protected void runInMavenJvm(List<String> argsList) throws MojoExecutionException {

        LaunchRunner launchRunner = new LaunchRunner(launcher, argsList, getLog());

        Thread launcherThread = launchRunner.run();
        launcherThread.setContextClassLoader(buildClassLoader(getClassPathUrls()));
        launcherThread.start();
        launchRunner.join();
        launchRunner.getThreadGroup().rethrowException();
    }

    /**
     * This will start VertX application in forked mode.
     *
     * @param argsList - the list of arguments that will be passed to the process
     * @return the exit code of the process run
     * @throws MojoExecutionException - any error that might occur while starting the process
     */
    protected int runAsForked(List<String> argsList) throws MojoExecutionException {
        ProcessRunner processRunner = new ProcessRunner(argsList, this.workDirectory, getLog(), true);
        int exitCode = processRunner.run();
        return exitCode;
    }

    /**
     * This add or build the classpath that will be passed to the forked process JVM i.e &quot;-cp&quot;
     *
     * @param args - the forked process argument list to which the classpath will be appended
     * @throws MojoExecutionException - any error that might occur while building or adding classpath
     */
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

    /**
     * This will add the ${project.build.outputDirectory} to the  classpath url collection
     *
     * @param classpathUrls - the existing classpath url collection to which the ${project.build.outputDirectory} be added
     * @throws IOException - any exception that might occur while get the classes directory as URL
     */
    private void addClassesDirectory(List<URL> classpathUrls) throws IOException {

        classpathUrls.add(this.classesDirectory.toURI().toURL());
    }

    /**
     * This will add the project resources typically ${basedir}/main/resources to the classpath url collection
     *
     * @param classpathUrls - the existing classpath url collection to which the ${project.build.outputDirectory} be added
     * @throws IOException - any exception that might occur while get the classes directory as URL
     */
    private void addProjectResources(List<URL> classpathUrls) throws IOException {

        for (Resource resource : this.project.getResources()) {
            File f = new File(resource.getDirectory());
            classpathUrls.add(f.toURI().toURL());
        }
    }

    /**
     * This will build the Vertx specific arguments that needs to be passed to the runnable process
     *
     * @param argsList - the existing collection of arguments to which the vertx arguments will be added
     */
    private void addVertxArgs(List<String> argsList) {

        Objects.requireNonNull(launcher);

        if (forked) {
            if (IO_VERTX_CORE_LAUNCHER.equals(launcher)) {
                argsList.add(IO_VERTX_CORE_LAUNCHER);
            } else {
                argsList.add(launcher);
            }
        }

        argsList.add(vertxCommand);

        //Since Verticles will be deployed from custom launchers we dont pass this as argument
        if (verticle != null) {
            argsList.add(verticle);
        }

        if (redeploy) {
            getLog().info("VertX application redeploy enabled");
            StringBuilder redeployArg = new StringBuilder();
            redeployArg.append(VERTX_ARG_REDEPLOY);
            redeployArg.append("=\"");
            if (redeployPatterns != null && redeployPatterns.isEmpty()) {
                final String redeployPattern = redeployPatterns.stream()
                        .collect(Collectors.joining(","))
                        .toString();
                argsList.add(redeployPattern);
            } else {
                Path patternFilePath = Paths.get(this.project.getBasedir().toString()
                        , VERTX_REDEPLOY_DEFAULT_PATTERN);
                redeployArg.append(patternFilePath.toString());
            }
            redeployArg.append("\"");
            argsList.add(redeployArg.toString());
        }

        argsList.add(VERTX_ARG_LAUNCHER_CLASS);
        argsList.add(launcher);

        if (conf != null && conf.exists() && conf.isFile()) {
            getLog().info("Using configuration from file: " + conf.toString());
            argsList.add(VERTX_ARG_CONF);
            argsList.add(conf.toString());
        }

    }

    private boolean isVertxLauncher(String launcher) throws MojoExecutionException {

        if (launcher != null) {
            if (IO_VERTX_CORE_LAUNCHER.equals(launcher)) {
                return true;
            } else {
                try {
                    Class customLauncher = buildClassLoader(getClassPathUrls()).loadClass(launcher);
                    List<Class<?>> superClasses = ClassUtils.getAllSuperclasses(customLauncher);
                    boolean isAssignable = superClasses != null && !superClasses.isEmpty();

                    for (Class<?> superClass : superClasses) {
                        if (IO_VERTX_CORE_LAUNCHER.equals(superClass.getName())) {
                            isAssignable = true;
                            break;
                        }
                    }
                    return isAssignable;
                } catch (ClassNotFoundException e) {
                    throw new MojoExecutionException("Class \"" + launcher + "\" not found");
                }
            }
        } else {
            return false;
        }
    }

    /**
     * This will build the {@link URLClassLoader} object from the collection of classpath URLS
     *
     * @param classPathUrls - the classpath urls which will be used to build the {@link URLClassLoader}
     * @return an instance of {@link URLClassLoader}
     * @throws MojoExecutionException - any error that might occur while building the {@link URLClassLoader}
     */
    private ClassLoader buildClassLoader(Collection<URL> classPathUrls) throws MojoExecutionException {
        return new URLClassLoader(classPathUrls.toArray(new URL[classPathUrls.size()]));
    }

    /**
     * This will resolve the project's test and runtime dependencies along with classes directory, resources directory
     * to the collection of classpath urls
     *
     * @return @{link {@link List<URL>}} which will have all the dependencies, classes directory, resources directory etc.,
     * @throws MojoExecutionException any error that might occur while building collection like resolution errors
     */
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

}
