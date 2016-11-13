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

package org.workspace7.maven.plugins.runners;

import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.workspace7.maven.plugins.utils.SignalListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author kameshs
 */
public class ProcessRunner {

    public static final int PROCESS_START_GRACE_TIMEOUT = 3;
    public static final int PROCESS_STOP_GRACE_TIMEOUT = 10;
    private static final Method INHERIT_IO_METHOD = MethodUtils.getAccessibleMethod(ProcessBuilder.class, "inheritIO");

    private final Path javaPath;
    private final File workDirectory;

    private List<String> argsList;

    private Process process;

    private Log logger;

    private CountDownLatch latch;

    public ProcessRunner(Log logger, File workDirectory, List<String> argsList) {
        this.logger = logger;
        this.argsList = argsList;
        javaPath = findJava();
        this.argsList.add(0, javaPath.toString());
        this.workDirectory = workDirectory;
        this.latch = new CountDownLatch(1);
    }

    public int run() throws MojoExecutionException {

        try {
            ProcessBuilder vertxRunProcBuilder = new ProcessBuilder(this.javaPath.toString())
                    .directory(this.workDirectory)
                    .command(argsList);

            boolean inheritedIO = inheritIO(vertxRunProcBuilder);

            Process vertxRunProc = vertxRunProcBuilder.start();
            this.process = vertxRunProc;
            //Attach Shutdown Hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(100L);
                    stopGracefully(PROCESS_STOP_GRACE_TIMEOUT, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    //nothing to do
                }
            }));

            if (!inheritedIO) {
                logger.info("Redirecting output to stdout...");
                redirectOutput(vertxRunProc);
            }

            SignalListener.handle(() -> handleSigInt());

            //Give some time for the process to be spawned
            awaitReadiness(PROCESS_START_GRACE_TIMEOUT, TimeUnit.SECONDS);

            if (!this.process.isAlive()) {
                throw new MojoExecutionException("Unable to start process");
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Error starting process", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Error starting process", e);
        }

        return 0;
    }


    protected void awaitReadiness(long timeout, TimeUnit timeUnit) throws InterruptedException {
        this.latch.await(timeout, timeUnit);
    }

    protected Path findJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            throw new RuntimeException("unable to locate java binary");
        }

        Path binDir = FileSystems.getDefault().getPath(javaHome, "bin");

        Path java = binDir.resolve("java.exe");
        if (java.toFile().exists()) {
            return java;
        }

        java = binDir.resolve("java");
        if (java.toFile().exists()) {
            return java;
        }

        throw new RuntimeException("unable to locate java binary");
    }

    protected void handleSigInt() {
        try {
            stopGracefully(PROCESS_STOP_GRACE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //cant do anything here
        }
    }

    /**
     * There's a bug in the Windows VM (https://bugs.openjdk.java.net/browse/JDK-8023130)
     * that means we need to avoid inheritIO
     * Thanks to SpringBoot Maven Plugin(https://github.com/spring-projects/spring-boot/blob/master/spring-boot-tools/spring-boot-maven-plugin)
     * for showing way to handle this
     */
    protected boolean isInheritIOBroken() {
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

    protected boolean inheritIO(ProcessBuilder processBuilder) {

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

    protected void redirectOutput(Process process) {

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

    protected void stopGracefully(int timeout, TimeUnit timeunit) throws InterruptedException {
        this.process.destroy();
        if (!this.process.waitFor(timeout, timeunit)) {
            this.process.destroyForcibly();
        }
    }


}
