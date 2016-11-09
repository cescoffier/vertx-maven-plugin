package org.workspace7.maven.plugins;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.wildfly.swarm.spi.api.JARArchive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author kameshs
 */
public class PackageHelper {

    private final JARArchive archive;
    private final Attributes.Name MAIN_VERTICLE = new Attributes.Name("Main-Verticle");
    private String mainVerticle;
    private String mainClass;

    public PackageHelper(String mainClass, String mainVerticle) {
        this.archive = ShrinkWrap.create(JARArchive.class);
        this.mainClass = mainClass;
        this.mainVerticle = mainVerticle;
    }

    public PackageHelper mainClass(String mainClass) {
        this.mainClass = mainClass;
        return this;
    }

    public PackageHelper mainVerticle(String mainVerticle) {
        this.mainVerticle = mainVerticle;
        return this;
    }

    public File build(String baseName, Path dir) throws IOException {
        build();
        return createFatJar(baseName, dir);
    }

    private Archive build() {
        generateManifest();
        return this.archive;
    }

    protected void generateManifest() {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        attributes.put(MAIN_VERTICLE, mainVerticle);

        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            manifest.write(bout);
            bout.close();
            byte[] bytes = bout.toByteArray();
            this.archive.addAsManifestResource(new ByteArrayAsset(bytes), "MANIFEST.MF");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private File createFatJar(String baseName, Path dir) throws IOException {

        File jarFile = new File(dir.toFile(), baseName + "-fat.jar");

        if (!jarFile.getParentFile().exists() && !jarFile.getParentFile().mkdirs()) {
            //TODO move to logging
            System.err.println("Failed to create parent directories for :" + jarFile.getAbsolutePath());
        }

        ZipExporter zipExporter = this.archive.as(ZipExporter.class);

        try (FileOutputStream jarOut = new FileOutputStream(jarFile)) {
            zipExporter.exportTo(jarOut);
        }

        return jarFile;
    }

}
