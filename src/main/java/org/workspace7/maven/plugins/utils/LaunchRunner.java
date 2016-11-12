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

}
