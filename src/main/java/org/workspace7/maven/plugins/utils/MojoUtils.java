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

package org.workspace7.maven.plugins.utils;


import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.util.Optional;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * @author kameshs
 */
public class MojoUtils {

    private static final String JAR_PLUGIN_KEY = "";
    private Log logger;

    public MojoUtils withLog(Log log) {
        this.logger = log;
        return this;
    }

    /**
     * @param project
     * @param mavenSession
     * @param buildPluginManager
     * @throws MojoExecutionException
     */
    public void buildPrimaryArtifact(MavenProject project, MavenSession mavenSession,
                                     BuildPluginManager buildPluginManager) throws MojoExecutionException {

        if (logger != null && logger.isDebugEnabled()) {
            logger.debug("Primary artifact does not exist, building ...");
        }

        String packaging = project.getPackaging();

        if ("jar".equals(packaging)) {

            Optional<Plugin> jarPlugin = hasJarPlugin(project);

            if (jarPlugin.isPresent()) {
                executeMojo(
                        jarPlugin.get(),
                        goal("jar:jar"),
                        configuration(element("outputDirectory", "${project.build.outputDir}"),
                                element("classesDirectory", "${project.build.outputDirectory}")),
                        executionEnvironment(project, mavenSession, buildPluginManager)
                );
            } else {
                executeMojo(
                        plugin("org.apache.maven.plugins", "maven-jar-plugin"),
                        goal("jar:jar"),
                        configuration(element("outputDirectory", "${project.build.outputDir}"),
                                element("classesDirectory", "${project.build.outputDirectory}")),
                        executionEnvironment(project, mavenSession, buildPluginManager)
                );
            }


        } else {
            throw new MojoExecutionException("The packaging :" + packaging + " is not supported as of now");
        }

        throw new MojoExecutionException("The packaging :" + packaging + " is not supported as of now");
    }

    /**
     * @param project
     * @return
     */
    private Optional<Plugin> hasJarPlugin(MavenProject project) {
        Optional<Plugin> jarPlugin = project.getBuildPlugins().stream()
                .filter(plugin -> JAR_PLUGIN_KEY.equals(plugin.getKey()))
                .findFirst();
        return jarPlugin;
    }
}
