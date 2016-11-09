package org.workspace7.maven.plugins;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.swarm.spi.api.JARArchive;

import java.io.*;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author kameshs
 */
public class PackageHelperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private PackageHelper packageHelper = new PackageHelper("io.vertx.core.Launcher", "org.example.demo.MainVerticle");

    public static List<String> read(InputStream input) throws IOException {
        try (BufferedReader buffer = new BufferedReader(new InputStreamReader(input))) {
            return buffer.lines().map(String::new).collect(Collectors.toList());
        }
    }

    @Test
    public void testManifest() throws Exception {

        File jarFile = packageHelper.build("test-vertx", Paths.get(temporaryFolder.getRoot().toURI()));

        JARArchive jarArchive = ShrinkWrap.createFromZipFile(JARArchive.class, jarFile);

        Asset mainfest = jarArchive.get("META-INF/MANIFEST.MF").getAsset();

        List<String> lines = read(mainfest.openStream());
        assertNotNull(lines);
        assertFalse(lines.isEmpty());
        assertTrue(lines.contains("Main-Verticle: org.example.demo.MainVerticle"));
        assertTrue(lines.contains("Main-Class: io.vertx.core.Launcher"));

        //System.out.printf("Manifest : " + lines.stream().collect(Collectors.joining("\n")));

    }
}
