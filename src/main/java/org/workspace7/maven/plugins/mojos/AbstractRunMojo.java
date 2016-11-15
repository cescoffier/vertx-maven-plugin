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

package org.workspace7.maven.plugins.mojos;

import org.apache.commons.lang3.ClassUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;
import org.workspace7.maven.plugins.runners.LaunchRunner;
import org.workspace7.maven.plugins.runners.ProcessRunner;
import org.workspace7.maven.plugins.utils.ConfigConverterUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AbstractRunMojo extends AbstractVertxMojo {

    /* ==== Vertx Program Args ==== */



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
    protected String vertxCommand = Constants.VERTX_COMMAND_RUN;

    protected List<String> argsList = new ArrayList<>();
    /**
     * This property will be passed as the -conf option to vertx run. It defaults to file
     * "src/main/conf/application.json", if it exists it will passed to the vertx run
     */
    @Parameter(alias = "config", property = "vertx.config", defaultValue = "src/main/conf/application.json")
    File conf;
    /**
     * This property will be used as the working directory for the process when running in forked mode.
     * This defaults to ${project.basedir}
     */
    @Parameter(alias = "workDirectory", property = "vertx.directory", defaultValue = "${project.basedir}")
    File workDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        scanAndLoadConfigs();

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

            runAsForked(argsList);

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
     * This add or build the classpath that will be passed to the forked process JVM i.e &quot;-cp&quot;
     *
     * @param args - the forked process argument list to which the classpath will be appended
     * @throws MojoExecutionException - any error that might occur while building or adding classpath
     */
    protected void addClasspath(List<String> args) throws MojoExecutionException {
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
    protected void addClassesDirectory(List<URL> classpathUrls) throws IOException {

        classpathUrls.add(this.classesDirectory.toURI().toURL());
    }

    /**
     * This will add the project resources typically ${basedir}/main/resources to the classpath url collection
     *
     * @param classpathUrls - the existing classpath url collection to which the ${project.build.outputDirectory} be added
     * @throws IOException - any exception that might occur while get the classes directory as URL
     */
    protected void addProjectResources(List<URL> classpathUrls) throws IOException {

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
    protected void addVertxArgs(List<String> argsList) {

        Objects.requireNonNull(launcher);

        if (forked) {
            if (Constants.IO_VERTX_CORE_LAUNCHER.equals(launcher)) {
                argsList.add(Constants.IO_VERTX_CORE_LAUNCHER);
            } else {
                argsList.add(launcher);
            }
        }

        argsList.add(vertxCommand);

        //Since Verticles will be deployed from custom launchers we dont pass this as argument
        if (verticle != null && !"stop".equals(vertxCommand)) {
            argsList.add(verticle);
        }

        if (redeploy && !("start".equals(vertxCommand) || "start".equals(vertxCommand))) {
            getLog().info("VertX application redeploy enabled");
            StringBuilder redeployArg = new StringBuilder();
            redeployArg.append(Constants.VERTX_ARG_REDEPLOY);
            redeployArg.append("=\"");
            if (redeployPatterns != null && redeployPatterns.isEmpty()) {
                final String redeployPattern = redeployPatterns.stream()
                        .collect(Collectors.joining(","))
                        .toString();
                argsList.add(redeployPattern);
            } else {
                Path patternFilePath = Paths.get(this.project.getBasedir().toString()
                        , Constants.VERTX_REDEPLOY_DEFAULT_PATTERN);
                redeployArg.append(patternFilePath.toString());
            }
            redeployArg.append("\"");
            argsList.add(redeployArg.toString());
        }

        if (!"stop".equals(vertxCommand)) {
            argsList.add(Constants.VERTX_ARG_LAUNCHER_CLASS);
            argsList.add(launcher);

            if (conf != null && conf.exists() && conf.isFile()) {
                getLog().info("Using configuration from file: " + conf.toString());
                argsList.add(Constants.VERTX_ARG_CONF);
                argsList.add(conf.toString());
            }
        }

    }

    /**
     * Method to check if the Launcher is {@link Constants#IO_VERTX_CORE_LAUNCHER} or instance of
     * {@link io.vertx.core.Launcher}
     *
     * @param launcher - the launcher class as string that needs to be checked
     * @return true if its {@link Constants#IO_VERTX_CORE_LAUNCHER} or instance of {@link io.vertx.core.Launcher}
     * @throws MojoExecutionException - any error that might occur while checking
     */
    protected boolean isVertxLauncher(String launcher) throws MojoExecutionException {

        if (launcher != null) {
            if (Constants.IO_VERTX_CORE_LAUNCHER.equals(launcher)) {
                return true;
            } else {
                try {
                    Class customLauncher = buildClassLoader(getClassPathUrls()).loadClass(launcher);
                    List<Class<?>> superClasses = ClassUtils.getAllSuperclasses(customLauncher);
                    boolean isAssignable = superClasses != null && !superClasses.isEmpty();

                    for (Class<?> superClass : superClasses) {
                        if (Constants.IO_VERTX_CORE_LAUNCHER.equals(superClass.getName())) {
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
    protected ProcessRunner runAsForked(List<String> argsList) throws MojoExecutionException {
        ProcessRunner processRunner = new ProcessRunner(argsList, this.workDirectory, getLog(), true);
        processRunner.run();
        return processRunner;
    }

    /**
     * This method to load Vert.X application configurations.
     * This will use the pattern ${basedir}/src/main/conf/application.[json/yaml/yml]
     */
    protected void scanAndLoadConfigs() throws MojoExecutionException {

        Path confBaseDir = Paths.get(this.project.getBasedir().toString(), "src", "main", "conf");

        if (Files.exists(confBaseDir) && Files.isDirectory(confBaseDir)) {

            DirectoryScanner directoryScanner = new DirectoryScanner();
            directoryScanner.setBasedir(this.project.getBasedir() + "/src/main/conf");
            directoryScanner.setIncludes(new String[]{"*.json", "*.yml", "*.yaml"});
            directoryScanner.scan();

            String[] configFiles = directoryScanner.getIncludedFiles();

            if (configFiles != null && configFiles.length != 0) {
                String configFile = configFiles[0];
                Path confPath = Paths.get(confBaseDir.toFile().toString(), configFile);
                //Check if its JSON
                if (isJson(configFile)) {
                    conf = confPath.toFile();
                    return;
                } else if (isYaml(configFile)) {
                    //Check if its YAML or YML
                    Path jsonConfDir = Paths.get(this.projectBuildDir, "conf");
                    jsonConfDir.toFile().mkdirs();
                    Path jsonConfPath = Paths.get(jsonConfDir.toString(), Constants.VERTX_CONFIG_FILE_JSON);
                    try {
                        if (Files.createFile(jsonConfPath).toFile().exists()) {
                            ConfigConverterUtil.convertYamlToJson(confPath, jsonConfPath);
                            conf = jsonConfPath.toFile();
                        }
                        return;
                    } catch (IOException e) {
                        throw new MojoExecutionException("Error loading configuration file:" + confPath.toString(), e);
                    } catch (Exception e) {
                        throw new MojoExecutionException("Error loading and converting configuration file:"
                                + confPath.toString(), e);
                    }
                }
            }
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


    /**
     * Method to check if the file is JSON file
     *
     * @param configFile - the config file to be checked
     * @return if its json file e.g. applicaiton.json
     */
    private boolean isJson(String configFile) {
        return configFile != null && configFile.endsWith(".json");
    }

    /**
     * Method to check if the file is YAML file
     *
     * @param configFile - the config file to be checked
     * @return if its YAML file e.g. application.yml or applicaiton.yml
     */
    private boolean isYaml(String configFile) {
        return configFile != null && (configFile.endsWith(".yaml") || configFile.endsWith(".yml"));
    }

}
