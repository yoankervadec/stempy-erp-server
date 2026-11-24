package com.lesconstructionssapete.stempyerpserver.app;

import java.util.function.ToIntFunction;

import com.lesconstructionssapete.stempyerpserver.app.config.AppSettings;
import com.lesconstructionssapete.stempyerpserver.modules.AllModules;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.module.ModuleHost;
import com.lesconstructionssapete.stempyerpserver.platform.module.ServiceRegistry;
import com.lesconstructionssapete.stempyerpserver.platform.audit.AuditSettings;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.Outbox;
import com.lesconstructionssapete.stempyerpserver.platform.events.outbox.OutboxSettings;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.Scheduler;
import com.lesconstructionssapete.stempyerpserver.platform.scheduler.SchedulerSettings;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Usage:
 *
 * <pre>
 * java -jar stempy.jar                                            // start the server
 * java -jar stempy.jar set-password USER_NO                       // set a user's password (prompts, no echo)
 * java -jar stempy.jar assign-role USER_NO COMPANY_CODE ROLE_CODE // give a user a role in a company
 * </pre>
 *
 * Every mode runs the same startup (modules, migrations, pool, services); a
 * command then stops short of the web server and the scheduler.
 */
public class Main {

  private static final String USAGE = """
      Usage:
        (no arguments)                               start the server
        set-password USER_NO                         set a user's password
        assign-role USER_NO COMPANY_CODE ROLE_CODE   give a user a role in a company""";

  public static void main(String[] args) {
    ToIntFunction<ServiceRegistry> command = command(args);

    Settings settings = AppSettings.load();
    var host = new ModuleHost(AllModules.create(settings, new PasswordHasher()));

    DatabaseConfig db = DatabaseConfig.from(settings);
    DatabaseMigrator.migrate(db);
    HikariDataSource dataSource = DataSources.create(db);
    host.start(new UnitOfWorkFactory(dataSource));

    if (command != null) {
      int exitCode;
      try (dataSource) {
        exitCode = command.applyAsInt(host.services());
      }
      System.exit(exitCode);
    }

    WebServer webServer = host.startWebServer(WebServerConfig.ofPort(settings.getInt("server.port")));
    host.configureAudit(AuditSettings.from(settings));
    Scheduler scheduler = host.startScheduler(SchedulerSettings.from(settings));
    Outbox outbox = host.startOutbox(OutboxSettings.from(settings));
    // Stop taking requests, starting jobs and delivering messages first, then release the pool they were using.
    // Requests and jobs may still enqueue messages while they finish, so the outbox stops last.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try (dataSource) {
        try {
          webServer.stop();
        } finally {
          try {
            scheduler.stop();
          } finally {
            outbox.stop();
          }
        }
      }
    }, "shutdown"));
  }

  /**
   * The CLI command to run, or null to start the server; exits 2 on bad usage.
   */
  private static ToIntFunction<ServiceRegistry> command(String[] args) {
    if (args.length == 0) {
      return null;
    }
    if (args[0].equals("set-password") && args.length == 2) {
      return services -> SetPasswordCommand.run(services.get(UsersApi.class), args[1]);
    }
    if (args[0].equals("assign-role") && args.length == 4) {
      return services -> AssignRoleCommand.run(services.get(UsersApi.class), args[1], args[2], args[3]);
    }
    System.err.println(USAGE);
    System.exit(2);
    return null; // unreachable
  }
}
