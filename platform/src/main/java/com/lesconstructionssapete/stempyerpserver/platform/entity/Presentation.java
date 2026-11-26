package com.lesconstructionssapete.stempyerpserver.platform.entity;

/** Where a client opens one record of an entity (docs/ARCHITECTURE.md §23.4). */
public enum Presentation {
  /** A dialog over the list: small records. */
  MODAL,
  /** A page of its own: documents with lines, or many fields. */
  PAGE
}
