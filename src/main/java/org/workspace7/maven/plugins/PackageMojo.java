/*
 *   Copyright 2016 Kamesh Sampath and Ronald Huss
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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.workspace7.maven.plugins.utils.PackageHelper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
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

    /* ==== Maven deps ==== */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}")
    protected String projectBuildDir;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected DefaultRepositorySystemSession repositorySystemSession;

    @Parameter(alias = "remoteRepositories", defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Component
    protected MavenProjectHelper mavenProjectHelper;

    @Component
    protected RepositorySystem repositorySystem;

    /* ==== Config ====  */
    // TODO-ROL: It would be awesome if this would not be required but, if not given,
    // the plugin tries to detect a single verticle. Maybe even decorated with a specific annotation ?
    // (like @MainVerticle ?). Only if no such verticle can be uniquely identified, then throw an exception.
    @Parameter(defaultValue = "${vertx.verticle}", required = true)
    protected String verticle;

    @Parameter(defaultValue = "io.vertx.core.Launcher", property = "vertx.launcher")
    protected String launcher;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final Artifact artifact = this.project.getArtifact();

        File primaryArtifactFile = getArtifactFile(artifact);

        //Step 0: Resolve and Collect Dependencies as g:a:v:t:c cordinates
        //FIXME handle exceptions in lambda and break processing

        Set<Optional<File>> compileAndRuntimeDeps = extractArtifactPaths(this.project.getDependencyArtifacts());
        Set<Optional<File>> transitiveDeps = extractArtifactPaths(this.project.getArtifacts());

        //TODO add Resource Directories

        PackageHelper packageHelper = new PackageHelper(this.launcher, this.verticle)
                .compileAndRuntimeDeps(compileAndRuntimeDeps)
                .transitiveDeps(transitiveDeps);

        //Step 1: build the jar add classifier and add it to project

        try {

            File fatJarFile = packageHelper
                    .log(getLog())
                    .build(this.project.getName(), /* name is always != null */
                            Paths.get(this.projectBuildDir), primaryArtifactFile);

            ArtifactHandler handler = new DefaultArtifactHandler("jar");

            Artifact vertxJarArtifact = new DefaultArtifact(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getBaseVersion(), artifact.getScope()
                    , "jar", "vertx", handler);
            vertxJarArtifact.setFile(fatJarFile);

            this.project.addAttachedArtifact(vertxJarArtifact);

        } catch (Exception e) {
            throw new MojoFailureException("Unable to build fat jar", e);
        }

    }

    private File getArtifactFile(Artifact artifact) throws MojoExecutionException {
        final String finalName = this.project.getName();
        if (artifact == null) {
            Path finalNameArtifact = Paths.get(this.projectBuildDir, finalName + "." + this.project.getPackaging());
            if (Files.exists(finalNameArtifact)) {
                return finalNameArtifact.toFile();
            }
        } else {
            return artifact.getFile();
        }
        // TODO-ROL: Maybe we should fork vertx:package to the package phase (and provide also a vertx:package-nofork for usage
        // in execution bindings) ?
        throw new MojoExecutionException("No primary artifact found, please run mvn package before running vertx-maven-plugin:package");
    }

    private Set<Optional<File>> extractArtifactPaths(Set<Artifact> artifacts) {
        return artifacts
                .stream()
                .filter(e -> e.getScope().equals("compile") || e.getScope().equals("runtime"))
                .map(artifact -> asMavenCoordinates(artifact))
                .map(s -> resolveArtifact(s))
                .collect(Collectors.toSet());
    }

    protected Optional<File> resolveArtifact(String artifact) {
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(new org.eclipse.aether.artifact.DefaultArtifact(artifact));
        try {
            ArtifactResult artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, artifactRequest);
            if (artifactResult.isResolved()) {
                getLog().info("Resolved :" + artifactResult.getArtifact().getArtifactId());
                return Optional.of(artifactResult.getArtifact().getFile());
            } else {
                getLog().error("Unable to resolve:" + artifact);
            }
        } catch (ArtifactResolutionException e) {
            getLog().error("Unable to resolve:" + artifact);
        }

        return Optional.empty();
    }

    protected String asMavenCoordinates(Artifact artifact) {
        // TODO-ROL: Shouldn't there be also the classified included after the groupId (if given ?)
        // Maybe we we should simply reuse DefaultArtifact.toString() (but could be too fragile as it might change
        // although I don't think it will change any time soon since probably many people already
        // rely on it)
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
