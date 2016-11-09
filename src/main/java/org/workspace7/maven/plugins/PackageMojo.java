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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.ArtifactRepository;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

    @Parameter(defaultValue = "${main.verticle}", required = true)
    protected String mainVerticle;

    @Parameter(defaultValue = "io.vertx.core.Launcher")
    protected String vertxLauncher;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected DefaultRepositorySystemSession repositorySystemSession;

    @Parameter(alias = "remoteRepositories", defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Inject
    protected ArtifactResolver artifactResolver;

    @Component
    protected RepositorySystem repositorySystem;

    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Hare Krishna!");
        final Artifact primaryArtifact = this.project.getArtifact();
        final String finalName = this.project.getName();
        final String type = primaryArtifact.getType();

        File primaryArtifactFile = this.project.getFile();

        if (primaryArtifact == null) {
            Path finalNameArtifact = Paths.get(this.projectBuildDir, finalName + "." + this.project.getPackaging());
            if (Files.exists(finalNameArtifact)) {
                primaryArtifactFile = finalNameArtifact.toFile();
            }
        }

        if (primaryArtifactFile == null) {
            throw new MojoExecutionException("No primary artifact found, please run mvn package before running vertx-maven-plugin:package");
        }


        //Step 1: build the jar
    }


}
