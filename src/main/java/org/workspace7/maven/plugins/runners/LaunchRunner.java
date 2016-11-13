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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @author kameshs
 */
public class LaunchRunner implements Runnable {

    private final String launcherClass;
    private final List<String> argList;

    public LaunchRunner(String launcherClass, List<String> argList) {
        this.launcherClass = launcherClass;
        this.argList = argList;
    }

    public static void join(IsolatedThreadGroup threadGroup) {
        boolean hasNoDaemonThreads;
        do {
            hasNoDaemonThreads = false;
            Thread[] threads = new Thread[threadGroup.activeCount()];
            threadGroup.enumerate(threads);
            for (Thread thread : threads) {
                if (thread != null && !thread.isDaemon()) {
                    try {
                        hasNoDaemonThreads = true;
                        thread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } while (hasNoDaemonThreads);
    }

    @Override
    public void run() {
        Thread thread = Thread.currentThread();
        ClassLoader classLoader = thread.getContextClassLoader();
        try {
            Class<?> launcherClazz = classLoader.loadClass(launcherClass);
            Method method = launcherClazz.getMethod("main", String[].class);
            String[] args = new String[argList.size()];
            args = argList.toArray(args);
            method.invoke(null, new Object[]{args});
        } catch (ClassNotFoundException e) {
            thread.getThreadGroup().uncaughtException(thread, e);
        } catch (NoSuchMethodException e) {
            Exception wrappedEx = new Exception("The class " + launcherClass + " does not have static main method " +
                    " that accepts String[]", e);
            thread.getThreadGroup().uncaughtException(thread, wrappedEx);
        } catch (InvocationTargetException e) {
            thread.getThreadGroup().uncaughtException(thread, e);
        } catch (IllegalAccessException e) {
            thread.getThreadGroup().uncaughtException(thread, e);
        }
    }

    /**
     * Isolated ThreadGroup to catch uncaught exceptions {@link ThreadGroup}
     */
    public static final class IsolatedThreadGroup extends ThreadGroup {

        private final Log logger;
        private Object monitor = new Object();
        private Throwable exception;

        public IsolatedThreadGroup(String name, Log logger) {
            super(name);
            this.logger = logger;
        }

        public void rethrowException() throws MojoExecutionException {
            synchronized (this.monitor) {
                if (this.exception != null) {
                    throw new MojoExecutionException("Error occurred while running.." +
                            this.exception.getMessage(), this.exception);
                }
            }
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (!(e instanceof ThreadDeath)) {
                synchronized (this.monitor) {
                    this.exception = (this.exception != null ? e : this.exception);
                }
                logger.warn(e);
            }
        }
    }

}
