package com.lesconstructionssapete.stempyerpserver.platform.entity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * TEST-ONLY helper: finds every class under a package that declares a static
 * EntityDefinition constant named DEF.
 * Used to prove that no entity definition was forgotten in a module's
 * registration hook.
 * Deliberately not used at startup (see notes): classpath scanning is fragile
 * in fat jars / native images.
 */
public class EntityDefinitionScanner {

  private EntityDefinitionScanner() {
  }

  public static List<Class<?>> findEntityClasses(String basePackage) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    String dir = basePackage.replace('.', '/');
    TreeSet<String> classNames = new TreeSet<>();
    try {
      Enumeration<URL> roots = cl.getResources(dir);
      for (URL url : Collections.list(roots)) {
        switch (url.getProtocol()) {
          case "file" -> collectFromDirectory(Path.of(url.toURI()), dir, classNames);
          case "jar" -> collectFromJar(url, dir, classNames);
          default -> throw new IllegalStateException("Unsupported classpath entry: " + url);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }

    List<Class<?>> result = new ArrayList<>();
    for (String name : classNames) {
      try {
        Class<?> c = Class.forName(name, false, cl); // do not initialize just to inspect
        if (declaresDef(c)) {
          result.add(c);
        }
      } catch (ClassNotFoundException | LinkageError ignored) {
        // not loadable in this context (e.g. optional dependency): cannot be an entity
        // we can register
      }
    }
    return result;
  }

  private static boolean declaresDef(Class<?> c) {
    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
      if (f.getName().equals("DEF")
          && Modifier.isStatic(f.getModifiers())
          && EntityDefinition.class.isAssignableFrom(f.getType())) {
        return true;
      }
    }
    return false;
  }

  private static void collectFromDirectory(Path root, String dir, TreeSet<String> out) throws IOException {
    // root points at <classes>/<dir>; names must be rebuilt relative to <classes>
    Path classesRoot = root;
    for (String _ : dir.split("/")) {
      classesRoot = classesRoot.getParent();
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : (Iterable<Path>) files::iterator) {
        String rel = classesRoot.relativize(p).toString().replace('\\', '/');
        if (rel.endsWith(".class") && !rel.endsWith("module-info.class")) {
          out.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
        }
      }
    }
  }

  private static void collectFromJar(URL url, String dir, TreeSet<String> out) throws IOException {
    JarURLConnection conn = (JarURLConnection) url.openConnection();
    conn.setUseCaches(false);
    try (JarFile jar = conn.getJarFile()) {
      for (JarEntry e : Collections.list(jar.entries())) {
        String n = e.getName();
        if (n.startsWith(dir + "/") && n.endsWith(".class") && !n.endsWith("module-info.class")) {
          out.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
        }
      }
    }
  }

}
