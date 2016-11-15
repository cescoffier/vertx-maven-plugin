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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.workspace7.maven.plugins.runners.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * This goal is used to stop the vertx application in background mode identified by vertx process id stored
 * in the project workingDirectory with name vertx-start-process.id
 *
 * @author kameshs
 */
@Mojo(name = "stop",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class StopMojo extends AbstractRunMojo {

    /**
     * this control how long the process should to start, if the process does not stop within the time, its deemed as
     * failed, the default value is 10 seconds
     */
    @Parameter(alias = "timeout", property = "vertx.stop.timeout", defaultValue = "10")
    protected int timeout;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        forked = true;
        vertxCommand = Constants.VERTX_COMMAND_STOP;

        String vertxProcId;

        try {
            byte[] bytes = Files.readAllBytes(Paths.get(workDirectory.toString(), Constants.VERTX_PID_FILE));
            vertxProcId = new String(bytes);
            addClasspath(argsList);

            if (isVertxLauncher(launcher)) {
                addVertxArgs(argsList);
            } else {
                argsList.add(launcher);
            }

            argsList.add(vertxProcId);

            ProcessRunner processRunner = runAsForked(argsList);

            processRunner.awaitReadiness(timeout, TimeUnit.SECONDS);

            if (processRunner.getProcess().isAlive()) {
                throw new MojoExecutionException("Unable to stop process within timeout :" + timeout + "seconds");
            }

            Files.delete(Paths.get(workDirectory.toString(), Constants.VERTX_PID_FILE));

        } catch (IOException e) {
            throw new MojoExecutionException("Unable to read process file from directory :" + workDirectory.toString());
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Unable to stop process", e);
        }
    }
}
