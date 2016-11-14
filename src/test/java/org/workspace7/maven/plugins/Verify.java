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

package org.workspace7.maven.plugins;

//import io.restassured.RestAssured;

import io.restassured.RestAssured;
import io.vertx.core.Launcher;
import org.apache.commons.lang3.ClassUtils;

import java.io.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author kameshs
 */
public class Verify {

    public static void verifyVertxJar(File jarFile) throws Exception {
        VertxJarVerifier vertxJarVerifier = new VertxJarVerifier(jarFile);
        vertxJarVerifier.verifyJarCreated();
        vertxJarVerifier.verifyManifest();
    }

    public static void verifyApplicationRunning() throws Exception {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = Integer.getInteger("http.port", 8080);
        RestAssured.get("/").then().assertThat().statusCode(200)
                .contentType("text/plain");
    }

    public static String read(InputStream input) throws IOException {
        try (BufferedReader buffer = new BufferedReader(new InputStreamReader(input))) {
            return buffer.lines().collect(Collectors.joining("\n"));
        }
    }

    public static Stream<String> readAsStream(InputStream input) throws IOException {
        try (BufferedReader buffer = new BufferedReader(new InputStreamReader(input))) {
            return buffer.lines().collect(Collectors.toList()).stream();
        }
    }

    public static String argsToString(String[] args) {
        return Stream.of(args).collect(Collectors.joining(" ")).toString();
    }

    public static void main(String[] args) {

        try {
            Class c = Verify.class.getClassLoader().loadClass("org.workspace7.maven.plugins.MyLauncher");
            boolean isAssignable = ClassUtils.isAssignable(c, Launcher.class);

            if (isAssignable) {
                System.out.println("yes i can ");
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

//        Launcher launcher = new Launcher();
//        launcher.getCommandNames().stream().forEach(s -> System.out.println(s));
//
        args = new String[]{"run", "org.workspace7.maven.plugins.SimpleVerticle", "--launcher-class=org.workspace7.maven.plugins.MyLauncher", "--redeploy=\"src/main/*.java\""};
        System.out.println(argsToString(args));

        MyLauncher.main(args);


    }

    public static class VertxJarVerifier {

        File jarFile;

        public VertxJarVerifier(File jarFile) {
            this.jarFile = jarFile;
        }

        protected void verifyJarCreated() throws Exception {
            assertThat(jarFile).isNotNull();
            assertThat(jarFile).isFile();
        }

        protected void verifyManifest() throws Exception {

            Manifest manifest = new JarFile(jarFile).getManifest();

            assertThat(manifest).isNotNull();
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            String mainVerticle = manifest.getMainAttributes().getValue("Main-Verticle");
            assertThat(mainClass).isNotNull().isEqualTo("io.vertx.core.Launcher");
            assertThat(mainVerticle).isNotNull().isEqualTo("org.vertx.demo.MainVerticle");

        }
    }
}
