package uk.gov.justice.laa.dstew.access.testutils.massdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;
import uk.gov.justice.laa.dstew.access.testutils.ApplicationLifecycle;

class ApplicationLifecycleSelectorTest {
  private final ApplicationLifecycleSelector selector = new ApplicationLifecycleSelector();

  @Test
  void selectsTheSameLifecyclesForTheSameSeed() {
    var first = RandomGeneratorFactory.getDefault().create(42L);
    var second = RandomGeneratorFactory.getDefault().create(42L);

    for (int index = 0; index < 100; index++) {
      assertThat(selector.select(first)).isEqualTo(selector.select(second));
    }
  }

  @Test
  void enforcesLifecycleInvariants() {
    assertThatThrownBy(() -> new ApplicationLifecycle(true, true, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApplicationLifecycle(false, false, false, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
