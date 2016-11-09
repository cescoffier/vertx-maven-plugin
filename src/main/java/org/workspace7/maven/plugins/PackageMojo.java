package org.workspace7.maven.plugins;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.ArtifactRepository;
import org.workspace7.maven.plugins.utils.PackageHelper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 */
@Mojo(name = "package",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class PackageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}")
    protected String projectBuildDir;

    @Parameter(defaultValue = "${verticle.main}", required = true)
    protected String mainVerticle;

    @Parameter(defaultValue = "io.vertx.core.Launcher", property = "vertx.launcher")
    protected String vertxLauncher;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected DefaultRepositorySystemSession repositorySystemSession;

    @Parameter(alias = "remoteRepositories", defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Component
    protected RepositorySystem repositorySystem;

    public void execute() throws MojoExecutionException, MojoFailureException {

        final Artifact primaryArtifact = this.project.getArtifact();
        final String finalName = this.project.getName();
        final String type = primaryArtifact.getType();

        File primaryArtifactFile = this.project.getArtifact().getFile();

        if (primaryArtifact == null) {
            Path finalNameArtifact = Paths.get(this.projectBuildDir, finalName + "." + this.project.getPackaging());
            if (Files.exists(finalNameArtifact)) {
                primaryArtifactFile = finalNameArtifact.toFile();
            }
        }

        if (primaryArtifactFile == null) {
            throw new MojoExecutionException("No primary artifact found, please run mvn package before running vertx-maven-plugin:package");
        }


        //Step 0: Resolve and Collect Dependencies as g:a:v:t:c cordinates
        Set<String> compileAndRuntimeDeps = this.project.getDependencyArtifacts()
                .stream()
                .filter(e -> e.getScope().equals("compile") || e.getScope().equals("runtime"))
                .map(artifact -> asMavenCoordinates(artifact))
                .collect(Collectors.toSet());

        Set<String> transitiveDeps = this.project.getArtifacts()
                .stream()
                .filter(e -> e.getScope().equals("compile") || e.getScope().equals("runtime"))
                .map(artifact -> asMavenCoordinates(artifact))
                .collect(Collectors.toSet());

        //TODO add Resource Directories

        PackageHelper packageHelper = new PackageHelper(this.vertxLauncher, this.mainVerticle)
                .compileAndRuntimeDeps(compileAndRuntimeDeps)
                .transitiveDeps(transitiveDeps);

        //Step 1: build the jar add classifier and add it to project

        try {

            File fatJarFile = packageHelper
                    .log(getLog())
                    .build(finalName == null ? primaryArtifact.getId() : finalName,
                            Paths.get(this.projectBuildDir), primaryArtifactFile);

            ArtifactHandler handler = new DefaultArtifactHandler("jar");

            Artifact vertxJarArtifact = new DefaultArtifact(primaryArtifact.getGroupId(),
                    primaryArtifact.getArtifactId(), primaryArtifact.getBaseVersion(), primaryArtifact.getScope()
                    , "jar", "vertx", handler);
            vertxJarArtifact.setFile(fatJarFile);

            this.project.addAttachedArtifact(vertxJarArtifact);

        } catch (Exception e) {
            throw new MojoFailureException("Unable to build fat jar", e);
        }


    }

    protected String asMavenCoordinates(Artifact artifact) {

        StringBuilder artifactCords = new StringBuilder().
                append(artifact.getGroupId())
                .append(":")
                .append(artifact.getArtifactId())
                .append(":")
                .append(artifact.getVersion());
        if (!"jar".equals(artifact.getType())) {
            artifactCords.append(":").append(artifact.getType());
        }
        if (artifact.hasClassifier()) {
            artifactCords.append(":").append(artifact.getClassifier());
        }
        return artifactCords.toString();
    }
}
