package com.lesconstructionssapete.stempyerpserver.modules;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.lesconstructionssapete.stempyerpserver.platform.db.Jdbc;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWork;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityDefinition;
import com.lesconstructionssapete.stempyerpserver.platform.events.DomainEvent;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

/**
 * The module boundaries and layer rules of docs/ARCHITECTURE.md §7 and §11,
 * checked on every build. Maven already enforces app -> modules -> platform ->
 * shared; these rules cover what lives together inside the modules artifact.
 *
 * A business module is the package of an ERPModule implementation (users,
 * masterdata.items, ...) with its subpackages; a new module is picked up
 * automatically. Packages outside every module (common, the root) are shared
 * code, and AllModules is the composition root.
 *
 * Naming conventions the rules rely on: *Module, *Api, *Controller, *Service
 * (orchestrators), *Repository, operations/ and events/ packages.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  static final String ROOT = "com.lesconstructionssapete.stempyerpserver.modules";

  /** Longest first, so a nested module wins over an enclosing one. */
  private static final List<String> MODULE_ROOTS = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(ROOT).stream()
      .filter(c -> c.isAssignableTo(ERPModule.class) && !c.isInterface())
      .map(JavaClass::getPackageName)
      .distinct()
      .sorted(Comparator.comparingInt(String::length).reversed())
      .toList();

  static Optional<String> moduleOf(JavaClass c) {
    String pkg = c.getPackageName();
    return MODULE_ROOTS.stream().filter(r -> pkg.equals(r) || pkg.startsWith(r + ".")).findFirst();
  }

  // ---- between modules ----

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule modules_use_each_other_only_through_api_events_and_entities = classes()
      .that().resideInAPackage(ROOT + "..").and().doNotHaveSimpleName("AllModules")
      .should(
          new ArchCondition<JavaClass>("use other modules only through their *Api interfaces, events and entities") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
              Optional<String> from = moduleOf(origin);
              for (Dependency d : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass().getBaseComponentType();
                Optional<String> to = moduleOf(target);
                if (to.isEmpty() || to.equals(from) || isPublicSurface(target, to.get())) {
                  continue;
                }
                events.add(SimpleConditionEvent.violated(d, d.getDescription()
                    + " -> reaches into module " + to.get() + "; use its *Api, events or entity DEF"));
              }
            }
          })
      .because("modules are isolated: other modules see a module's public API, its events and its entities "
          + "(for relations), never its services, operations, repositories or DTOs");

  private static boolean isPublicSurface(JavaClass target, String module) {
    // The *Api interface and the types nested in it (its requests and results).
    JavaClass outer = topLevel(target);
    boolean api = outer.isInterface() && outer.getSimpleName().endsWith("Api")
        && outer.getPackageName().equals(module);
    boolean event = target.getPackageName().startsWith(module + ".events");
    boolean entity = target.tryGetField("DEF").map(f -> f.getRawType().isAssignableTo(EntityDefinition.class))
        .orElse(false);
    return api || event || entity;
  }

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule no_cycles_between_modules = slices()
      .assignedFrom(new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass c) {
          return moduleOf(c).map(SliceIdentifier::of).orElse(SliceIdentifier.ignore());
        }

        @Override
        public String getDescription() {
          return "business modules";
        }
      })
      .should().beFreeOfCycles()
      .because("module order in AllModules is a dependency order");

  // ---- layers inside a module ----

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule operations_never_use_other_operations = classes()
      .that().resideInAPackage("..operations..")
      .should(new ArchCondition<JavaClass>("not depend on another operation") {
        @Override
        public void check(JavaClass origin, ConditionEvents events) {
          JavaClass self = topLevel(origin);
          for (Dependency d : origin.getDirectDependenciesFromSelf()) {
            JavaClass target = d.getTargetClass().getBaseComponentType();
            if (target.getPackageName().contains(".operations") && !topLevel(target).equals(self)) {
              events.add(SimpleConditionEvent.violated(d, d.getDescription()));
            }
          }
        }
      })
      .because("operations are orchestrated, never chained: the orchestrator calls both, "
          + "or shared logic moves to a helper");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule operations_neither_publish_events_nor_manage_transactions = noClasses()
      .that().resideInAPackage("..operations..")
      .should().dependOnClassesThat().belongToAnyOf(EventBus.class, UnitOfWorkFactory.class)
      .because("the orchestrator owns the transaction and emits the events");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule operations_are_used_by_orchestrators_and_module_classes_only = classes()
      .that().resideInAPackage("..operations..")
      .should().onlyHaveDependentClassesThat(resideInAPackage("..operations..")
          .or(partOf("Service"))
          .or(partOfAModuleClass()))
      .because("controllers call orchestrators (*Service), which call operations");

  /**
   * The repository classes themselves: the records nested in them are result
   * shapes (LoginCandidate, RoleGrants...) and may flow up to orchestrators and
   * pure helpers.
   */
  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule repositories_are_used_by_operations_and_module_classes_only = classes()
      .that().haveSimpleNameEndingWith("Repository")
      .should().onlyHaveDependentClassesThat(resideInAPackage("..operations..")
          .or(partOf("Repository"))
          .or(partOfAModuleClass()))
      .because("only operations reach the database; the module class only builds the repositories");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule only_repositories_touch_sql = noClasses()
      .that(not(partOf("Repository")))
      .should().dependOnClassesThat().resideInAPackage("java.sql..")
      .orShould().dependOnClassesThat().belongToAnyOf(Jdbc.class)
      .orShould().callMethod(UnitOfWork.class, "connection")
      .because("SQL lives in repositories (docs/ARCHITECTURE.md §7)");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule modules_use_only_the_public_scheduler_api = noClasses()
      .that().resideInAPackage(ROOT + "..")
      .should().dependOnClassesThat().resideInAPackage("..platform.scheduler.internal..")
      .because("the scheduler's engine and its SQL are platform internals; modules use Scheduler, JobType, "
          + "JobContext and the schedule package");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule modules_use_only_the_public_outbox_api = noClasses()
      .that().resideInAPackage(ROOT + "..")
      .should().dependOnClassesThat().resideInAPackage("..platform.events.outbox.internal..")
      .because("the outbox dispatcher and its SQL are platform internals; modules use "
          + "EventBus.subscribeAsync and OutboxDelivery");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule only_module_classes_see_the_service_registry = noClasses()
      .that(not(partOfAModuleClass()))
      .should().dependOnClassesThat().belongToAnyOf(ServiceRegistry.class)
      .because("the module class resolves what it needs once and passes plain constructor arguments down");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule only_controllers_know_about_http = noClasses()
      .that(not(partOf("Controller")))
      .should().dependOnClassesThat().resideInAPackage("io.javalin..")
      .because("business code stays HTTP-free; controllers translate requests and responses");

  @ArchTest
  @SuppressWarnings("unused")
  static final ArchRule events_are_immutable_domain_event_records = classes()
      .that().resideInAPackage("..events..").and().areTopLevelClasses()
      .should().beRecords().andShould().implement(DomainEvent.class)
      .allowEmptyShould(true)
      .because("events are a module's public contract; listeners must not be able to change them");

  // ---- helpers ----

  /**
   * The class, or the class it is nested in (records, lambdas and anonymous
   * classes declared inside it), is named *{suffix}: nested classes belong to
   * their outer class's layer.
   */
  private static DescribedPredicate<JavaClass> partOf(String suffix) {
    return describe("are (nested in) *" + suffix, c -> topLevel(c).getSimpleName().endsWith(suffix));
  }

  private static DescribedPredicate<JavaClass> partOfAModuleClass() {
    return describe("are (nested in) an ERPModule", c -> topLevel(c).isAssignableTo(ERPModule.class));
  }

  private static JavaClass topLevel(JavaClass c) {
    JavaClass current = c;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
