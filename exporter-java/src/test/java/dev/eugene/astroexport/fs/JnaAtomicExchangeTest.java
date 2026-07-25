package dev.eugene.astroexport.fs;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.eugene.astroexport.fs.AtomicExchange.AtomicExchangeUnavailableException;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class JnaAtomicExchangeTest {
  @ParameterizedTest
  @ValueSource(ints = {45, 78, 102})
  void macOsUnsupportedErrnosMapToUnavailable(int errorNumber) {
    IOException error = JnaAtomicExchange.exchangeFailure(true, errorNumber);

    assertInstanceOf(AtomicExchangeUnavailableException.class, error);
  }

  @ParameterizedTest
  @ValueSource(ints = {38, 95})
  void linuxUnsupportedErrnosMapToUnavailable(int errorNumber) {
    IOException error = JnaAtomicExchange.exchangeFailure(false, errorNumber);

    assertInstanceOf(AtomicExchangeUnavailableException.class, error);
  }

  @ParameterizedTest
  @ValueSource(ints = {18, 22})
  void sharedUnsupportedErrnosMapToUnavailableOnBothPlatforms(int errorNumber) {
    assertInstanceOf(
        AtomicExchangeUnavailableException.class,
        JnaAtomicExchange.exchangeFailure(true, errorNumber));
    assertInstanceOf(
        AtomicExchangeUnavailableException.class,
        JnaAtomicExchange.exchangeFailure(false, errorNumber));
  }

  @ParameterizedTest
  @ValueSource(ints = {38, 95})
  void macOsDoesNotUseLinuxOnlyUnsupportedErrnos(int errorNumber) {
    IOException error = JnaAtomicExchange.exchangeFailure(true, errorNumber);

    assertFalse(error instanceof AtomicExchangeUnavailableException);
  }
}
