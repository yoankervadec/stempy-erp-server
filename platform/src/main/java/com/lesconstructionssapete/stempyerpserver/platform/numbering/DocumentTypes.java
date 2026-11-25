package com.lesconstructionssapete.stempyerpserver.platform.numbering;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every document type, registered by the modules in
 * {@code ERPModule.registerDocumentTypes} and frozen by ModuleHost before any
 * service exists. Thread-safe.
 */
public final class DocumentTypes {

  private final Map<String, DocumentType> types = new LinkedHashMap<>();
  private volatile boolean frozen;

  /**
   * @throws IllegalArgumentException if the declaration is inconsistent (see
   *                                  DocumentType)
   * @throws IllegalStateException    after freeze(), or for a key registered
   *                                  twice
   */
  public synchronized void register(DocumentType type) {
    Objects.requireNonNull(type, "type");
    if (frozen) {
      throw new IllegalStateException("DocumentTypes is frozen; register document types in "
          + "ERPModule.registerDocumentTypes");
    }
    if (types.containsKey(type.key())) {
      throw new IllegalStateException("Document type " + type.key() + " is registered twice");
    }
    type.validate();
    types.put(type.key(), type);
  }

  /** Idempotent. */
  public synchronized void freeze() {
    frozen = true;
  }

  public synchronized Optional<DocumentType> find(String key) {
    return Optional.ofNullable(types.get(key));
  }

  /** @throws IllegalStateException for a key no module registered: a wiring bug, not a client error. */
  public DocumentType require(String key) {
    return find(key).orElseThrow(() -> new IllegalStateException("Document type " + key
        + " is not registered: declare it in its module's registerDocumentTypes"));
  }

  /** In registration order. */
  public synchronized Collection<DocumentType> all() {
    return Collections.unmodifiableCollection(new ArrayList<>(types.values()));
  }
}
