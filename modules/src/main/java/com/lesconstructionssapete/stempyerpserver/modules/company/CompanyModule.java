package com.lesconstructionssapete.stempyerpserver.modules.company;

import com.lesconstructionssapete.stempyerpserver.modules.company.operations.CheckRateDateTakenOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindBaseCurrencyIdOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindBaseCurrencyOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindCurrencyCodeOperation;
import com.lesconstructionssapete.stempyerpserver.modules.company.operations.FindExchangeRateOperation;
import com.lesconstructionssapete.stempyerpserver.platform.entity.EntityRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.events.EventBus;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.security.CachedCompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.CompanyAccess;
import com.lesconstructionssapete.stempyerpserver.platform.security.PermissionRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints;
import com.lesconstructionssapete.stempyerpserver.platform.web.EntityEndpoints.Action;
import com.lesconstructionssapete.stempyerpserver.platform.web.RecordLayout;
import com.lesconstructionssapete.stempyerpserver.platform.web.Routes;
import com.lesconstructionssapete.stempyerpserver.platform.write.EntityChanged;

/**
 * The company: its own settings and its exchange rates (docs/ARCHITECTURE.md
 * §25). Both on the generic entity routes:
 *
 * <pre>
 * QUERY /company/companies        GET / PATCH /company/companies/{id}          company.settings.read / .update
 * QUERY /company/exchange-rates   GET / PATCH / DELETE .../{id}, POST         company.exchangeRate.*
 * </pre>
 *
 * The companies list holds one row, the request's company. Publishes
 * CompanyApi (base currency, rates, conversion) for the modules after it.
 *
 * Depends on users (created_by / updated_by relations, the permission cache)
 * and masterdata.currencies (rates point at currencies).
 */
public class CompanyModule implements ERPModule {

  static final String SETTINGS = "company.settings";
  static final String RATES = "company.exchangeRate";

  private final CompanyRepository repository = new CompanyRepository();
  private final FindBaseCurrencyOperation findBaseCurrency = new FindBaseCurrencyOperation(repository);

  @Override
  public String name() {
    return "company";
  }

  @Override
  public void registerPermissions(PermissionRegistry permissions) {
    permissions
        .declare(SETTINGS + ".read", "View the company's settings")
        .declare(SETTINGS + ".update", "Change the company's settings")
        .declareEntity(RATES, "exchange rates");
  }

  @Override
  public void registerEntities(EntityRegistry entityRegistry) {
    entityRegistry.registerAll(Company.class, ExchangeRate.class);
  }

  @Override
  public void registerServices(ServiceRegistry serviceRegistry) {
    serviceRegistry.register(CompanyApi.class,
        new CompanyApiService(findBaseCurrency, new FindBaseCurrencyIdOperation(repository),
            new FindExchangeRateOperation(repository)));
  }

  @Override
  public void registerEventHandlers(EventBus eventBus, ServiceRegistry services) {
    // Requests learn the company's default language through the permission cache: a change empties it.
    if (services.get(CompanyAccess.class) instanceof CachedCompanyAccess cache) {
      eventBus.subscribe(EntityChanged.class, (uow, e) -> {
        if (e instanceof EntityChanged.Updated u && u.entity().equals(Company.DEF.name())
            && u.changes().containsKey(Company.DEFAULT_LANGUAGE.name())) {
          cache.invalidateAll();
        }
      });
    }
  }

  @Override
  public void registerRoutes(Routes routes, ServiceRegistry serviceRegistry) {
    EntityEndpoints crud = serviceRegistry.get(EntityEndpoints.class);
    crud.register(routes, "/company/companies", Company.DEF, SETTINGS, Action.READ, Action.UPDATE);
    crud.layout(Company.DEF, RecordLayout.of(Company.DEF)
        .section("general", 2, Company.CODE, Company.DESCRIPTION, Company.BASE_CURRENCY_CODE,
            Company.DEFAULT_LANGUAGE)
        .tab(RecordLayout.HISTORY));

    crud.register(routes, "/company/exchange-rates", ExchangeRate.DEF, RATES);
    crud.rules(ExchangeRate.DEF, new ExchangeRateService(findBaseCurrency,
        new FindCurrencyCodeOperation(repository), new CheckRateDateTakenOperation(repository)));
    crud.layout(ExchangeRate.DEF, RecordLayout.of(ExchangeRate.DEF)
        .section("general", 3, ExchangeRate.CURRENCY_ID, ExchangeRate.STARTING_DATE, ExchangeRate.RATE)
        .tab(RecordLayout.HISTORY));
  }
}
