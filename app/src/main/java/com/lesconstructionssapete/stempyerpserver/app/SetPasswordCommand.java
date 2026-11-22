package com.lesconstructionssapete.stempyerpserver.app;

import java.io.Console;
import java.util.Arrays;

import com.lesconstructionssapete.stempyerpserver.modules.users.AuthService;
import com.lesconstructionssapete.stempyerpserver.modules.users.UsersApi;
import com.lesconstructionssapete.stempyerpserver.platform.error.ErpException;
import com.lesconstructionssapete.stempyerpserver.platform.error.ValidationFailedException;

/**
 * {@code set-password USER_NO}: prompts twice without echo, so the password
 * never lands in shell history or logs.
 */
final class SetPasswordCommand {

  private SetPasswordCommand() {
  }

  /** Returns the process exit code. */
  static int run(UsersApi users, String userNo) {
    Console console = System.console();
    if (console == null) {
      System.err.println("set-password needs an interactive terminal (no console attached)");
      return 1;
    }
    console.printf("Passwords must be %d to %d characters.%n",
        AuthService.MIN_PASSWORD_LENGTH, AuthService.MAX_PASSWORD_LENGTH);
    char[] first = console.readPassword("New password for %s: ", userNo);
    char[] second = console.readPassword("Repeat it: ");
    try {
      if (first == null || second == null || !Arrays.equals(first, second)) {
        System.err.println("Passwords don't match; nothing changed.");
        return 1;
      }
      users.setPassword(userNo, first); // wipes it
      System.out.println("Password set for " + userNo + "; their existing sessions were ended.");
      return 0;
    } catch (ValidationFailedException e) {
      // The message alone is just "Validation failed"; the reasons are per field.
      e.fieldErrors().forEach(fe -> System.err.println(fe.field() + " " + fe.message()));
      System.err.println("Nothing changed.");
      return 1;
    } catch (ErpException e) {
      System.err.println(e.getMessage());
      return 1;
    } finally {
      wipe(first);
      wipe(second);
    }
  }

  private static void wipe(char[] chars) {
    if (chars != null) {
      Arrays.fill(chars, '\0');
    }
  }
}
