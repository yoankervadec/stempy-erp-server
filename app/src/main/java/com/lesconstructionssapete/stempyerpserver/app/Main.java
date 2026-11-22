package com.lesconstructionssapete.stempyerpserver.app;

import com.lesconstructionssapete.stempyerpserver.app.config.AppSettings;
import com.lesconstructionssapete.stempyerpserver.modules.AllModules;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.config.Settings;
import com.lesconstructionssapete.stempyerpserver.platform.db.DataSources;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseConfig;
import com.lesconstructionssapete.stempyerpserver.platform.db.DatabaseMigrator;
import com.lesconstructionssapete.stempyerpserver.platform.db.UnitOfWorkFactory;
import com.lesconstructionssapete.stempyerpserver.platform.module.ModuleHost;
import com.lesconstructionssapete.stempyerpserver.platform.security.PasswordHasher;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServer;
import com.lesconstructionssapete.stempyerpserver.platform.web.WebServerConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Usage:
 *
 * <pre>
 * java -jar stempy.jar                       start the server
 * java -jar stempy.jar set-password USER_NO  set a user's password (prompts, no echo)
 * </pre>
 *
 * Both run the same startup (modules, migrations, pool, services); the
 * command then stops short of the web server.
 */
public class Main {

  public static void main(String[] args) {
    boolean setPassword = args.length == 2 && args[0].equals("set-password");
    if (args.length > 0 && !setPassword) {
      System.err.println("Usage: (no arguments) to start the server, or: set-password USER_NO");
      System.exit(2);
    }

    Settings settings = AppSettings.load();
    var host = new ModuleHost(AllModules.create(settings, new PasswordHasher()));

    DatabaseConfig db = DatabaseConfig.from(settings);
    DatabaseMigrator.migrate(db);
    HikariDataSource dataSource = DataSources.create(db);
    host.start(new UnitOfWorkFactory(dataSource));

    if (setPassword) {
      int exitCode;
      try (dataSource) {
        exitCode = SetPasswordCommand.run(host.services().get(UsersApi.class), args[1]);
      }
      System.exit(exitCode);
    }

    WebServer webServer = host.startWebServer(WebServerConfig.ofPort(settings.getInt("server.port")));
    // Stop taking requests first, then release the pool they were using.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try (dataSource) {
        webServer.stop();
      }
    }, "shutdown"));
  }
}
