package com.lesconstructionssapete.stempyerpserver.modules;

import java.time.Clock;
import java.util.List;

import com.lesconstructionssapete.stempyerpserver.modules.gl.GlModule;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.currencies.CurrenciesModule;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.items.ItemsModule;
import com.lesconstructionssapete.stempyerpserver.modules.masterdata.units.UnitsModule;
import com.lesconstructionssapete.stempyerpserver.modules.system.numbering.NumberingModule;
import com.lesconstructionssapete.stempyerpserver.modules.system.scheduling.SchedulingModule;
import com.lesconstructionssapete.stempyerpserver.modules.tax.TaxModule;
import com.lesconstructionssapete.stempyerpserver.modules.users.AuthSettings;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersModule;
import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.module.ERPModule;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;

/**
 * THE list of business modules, used by Main and by the tests, so there is
 * one place to add a module.
 *
 * Dependency order: a module may only use what the modules listed BEFORE it
 * registered (services, and entities its relations point at).
 */
public final class AllModules {

  private AllModules() {
  }

  /**
   * @param settings each module reads its own keys (e.g. auth.*)
   * @param hasher   production passes {@code new PasswordHasher()}; tests a
   *                 cheap one
   */
  public static List<ERPModule> create(Settings settings, PasswordHasher hasher) {
    return List.of(
        new UsersModule(AuthSettings.from(settings), hasher, Clock.systemUTC()),
        new TaxModule(),
        new CurrenciesModule(),
        new UnitsModule(),
        new ItemsModule(),
        new SchedulingModule(Clock.systemUTC()),
        new NumberingModule(Clock.systemUTC()),
        new GlModule(Clock.systemUTC()));
  }
}
