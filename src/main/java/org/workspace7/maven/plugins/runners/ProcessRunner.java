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
import org.workspace7.maven.plugins.utils.SignalListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @author kameshs
 */
public class ProcessRunner {

    private static final Method INHERIT_IO_METHOD = MethodUtils.getAccessibleMethod(ProcessBuilder.class, "inheritIO");

    boolean waitFor;

    List<String> argsList;

    Process process;

    long endTime;

    public ProcessRunner(boolean waitFor, List<String> argsList) {
        this.waitFor = waitFor;
        this.argsList = argsList;
    }

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

    public int run() throws IOException, InterruptedException {

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
        } finally {
            if (waitFor) {
                this.endTime = System.currentTimeMillis();
                this.process = null;
            }
        }
        return 0;
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

    final class RunProcessKiller implements Runnable {

        @Override
        public void run() {
            doKill();
        }

    }

}
