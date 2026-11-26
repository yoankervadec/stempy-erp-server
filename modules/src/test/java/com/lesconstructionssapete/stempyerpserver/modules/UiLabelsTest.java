package com.lesconstructionssapete.stempyerpserver.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.entity.Field;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.LabelKeys;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Language;
import com.lesconstructionssapete.stempyerpserver.platform.i18n.Messages;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ModuleHost;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;

/**
 * Every label a screen can show exists in every language
 * (docs/ARCHITECTURE.md §23.5): a missing translation fails the build, not a
 * user. Covers every entity a module registers (resources and not yet), its
 * fields and enum values, and the module's menu group.
 */
class UiLabelsTest {

  private static final List<ERPModule> MODULES = AllModules.create(Settings.empty(), new PasswordHasher(1_000));
  private static final Messages MESSAGES = new ModuleHost(MODULES).messages();

  /** The keys that must have a text, each as alternatives (a resource's own key, then the shared one). */
  private static List<String[]> requiredKeys() {
    List<String[]> keys = new ArrayList<>();
    for (ERPModule m : MODULES) {
      EntityRegistry registry = new EntityRegistry();
      m.registerEntities(registry);
      if (!registry.all().isEmpty()) {
        keys.add(new String[] { LabelKeys.module(m.name()) });
      }
      for (EntityDefinition<?> entity : registry.all()) {
        keys.add(new String[] { LabelKeys.resource(entity.name()) });
        keys.add(new String[] { LabelKeys.resourceOne(entity.name()) });
        for (Field<?, ?> f : entity.fields()) {
          if (f.isInternal()) {
            continue;
          }
          keys.add(LabelKeys.field(entity.name(), f.name()));
          Object[] constants = f.type().javaType().getEnumConstants();
          if (constants != null) {
            for (Object c : constants) {
              keys.add(new String[] { LabelKeys.enumValue((Enum<?>) c) });
            }
          }
        }
      }
    }
    return keys;
  }

  @Test
  void everyLabelExistsInEveryLanguage() {
    Set<String> missing = new TreeSet<>();
    for (Language language : Language.values()) {
      Map<String, String> own = MESSAGES.all(language);
      for (String[] alternatives : requiredKeys()) {
        boolean found = false;
        for (String key : alternatives) {
          found |= own.containsKey(key);
        }
        if (!found) {
          missing.add(language.code() + ": " + String.join(" or ", alternatives));
        }
      }
    }
    assertTrue(missing.isEmpty(), "missing labels:\n" + String.join("\n", missing));
  }

  @Test
  void everyLanguageHasTheSameLabelKeys() {
    Set<String> english = labelKeys(Language.EN);
    for (Language language : Language.values()) {
      assertEquals(english, labelKeys(language), "label keys differ between en and " + language.code());
    }
  }

  @Test
  void englishBundlesHoldNoErrorTexts() {
    // English error messages are the code's own (Messages.error): an English error.* entry would be ignored
    for (String key : MESSAGES.all(Language.EN).keySet()) {
      assertTrue(!key.startsWith("error.") && !key.startsWith("status."), key + " in an English bundle");
    }
  }

  @Test
  void translatedErrorsNameOnlyUpperSnakeCaseCodes() {
    for (String key : MESSAGES.all(Language.FR).keySet()) {
      if (key.startsWith("error.")) {
        assertTrue(key.substring(6).matches("[A-Z][A-Z0-9_]*"), key);
      }
    }
  }

  /** Everything but the error texts, which only translations have. */
  private static Set<String> labelKeys(Language language) {
    Set<String> out = new TreeSet<>();
    for (String key : MESSAGES.all(language).keySet()) {
      if (!key.startsWith("error.") && !key.startsWith("status.")) {
        out.add(key);
      }
    }
    return out;
  }
}
