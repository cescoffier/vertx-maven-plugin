package org.workspace7.maven.plugins.utils;

import org.apache.maven.plugin.logging.Log;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author kameshs
 */
public class PackageHelper {

    private final JavaArchive archive;
    private final Attributes.Name MAIN_VERTICLE = new Attributes.Name("Main-Verticle");
    private String mainVerticle;
    private String mainClass;
    private Set<String> compileAndRuntimeDeps;
    private Set<String> transitiveDeps;
    private Log log;

    public PackageHelper(String mainClass, String mainVerticle) {
        this.archive = ShrinkWrap.create(JavaArchive.class);
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

    public File build(String baseName, Path dir, File primaryArtifactFile) throws IOException {
        build(primaryArtifactFile);
        return createFatJar(baseName, dir);
    }

    private synchronized void build(File primaryArtifactFile) {
        this.archive.as(ZipImporter.class).importFrom(primaryArtifactFile);
        generateManifest();
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
            //TODO: merge existing manifest with current one
            this.archive.setManifest(new ByteArrayAsset(bytes));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private synchronized File createFatJar(String baseName, Path dir) {

        File jarFile = null;

        try {

            jarFile = new File(dir.toFile(), baseName + "-fat.jar");

            if (!jarFile.getParentFile().exists() && !jarFile.getParentFile().mkdirs()) {
                log.error("Failed to create parent directories for :" + jarFile.getAbsolutePath());
            }

            ZipExporter zipExporter = this.archive.as(ZipExporter.class);

            try (FileOutputStream jarOut = new FileOutputStream(jarFile)) {
                zipExporter.exportTo(jarOut);
            }
        } catch (IOException e) {
            log.error("Error building fat jar ", e);
        }

        return jarFile;
    }

    public PackageHelper compileAndRuntimeDeps(Set<String> compileAndRuntimeDeps) {

        this.compileAndRuntimeDeps = compileAndRuntimeDeps;

        return this;
    }

    public PackageHelper transitiveDeps(Set<String> transitiveDeps) {

        this.transitiveDeps = transitiveDeps;

        return this;
    }

    public PackageHelper log(Log log) {
        this.log = log;
        return this;
    }
}
