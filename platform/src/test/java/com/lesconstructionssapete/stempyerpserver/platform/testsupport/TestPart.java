package com.lesconstructionssapete.stempyerpserver.platform.testsupport;

import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Presentation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.SortKey;
import com.lesconstructionssapete.stempyerpserver.platform.entity.StandardEntity;

/** A standard business entity (audit columns, version, soft delete) for write tests. */
public final class TestPart extends StandardEntity {

  public static final EntityDefinition<TestPart> DEF = StandardEntity.define("part", "parts", TestPart::new);

  public static final Field<TestPart, String> CODE = DEF.string("code", "code", p -> p.code, (p, v) -> p.code = v)
      .required().unique().immutable().maxLength(10).sortable();
  public static final Field<TestPart, String> NAME = DEF.string("name", "name", p -> p.name, (p, v) -> p.name = v)
      .required();
  public static final Field<TestPart, String> NOTE = DEF.string("note", "note", p -> p.note, (p, v) -> p.note = v)
      .writePermission("part.note.write").multiline();

  static {
    DEF.titleField(CODE).subtitleField(NAME).listColumns(CODE, NAME).defaultSort(SortKey.asc(CODE))
        .presentation(Presentation.PAGE);
  }

  public String code;
  public String name;
  public String note;
}
