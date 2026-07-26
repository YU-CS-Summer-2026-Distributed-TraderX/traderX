package finos.traderx.positionservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finos.traderx.positionservice.model.Position;
import finos.traderx.positionservice.repository.PositionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * position-service is a thin read projection over the positions table, so its tests are honest and
 * small: they assert the query is scoped to the requested account (a getAllPositions where a
 * by-account read was intended would silently leak every account's book) and that results pass
 * through untouched. No Spring context, no DB — the repository is mocked.
 */
@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock private PositionRepository positionRepository;

  @InjectMocks private PositionService positionService;

  private static Position position(int account, String security, int qty) {
    Position p = new Position();
    p.setAccountId(account);
    p.setSecurity(security);
    p.setQuantity(qty);
    return p;
  }

  @Test
  void getPositionsByAccountID_scopesQueryToThatAccount() {
    List<Position> book = List.of(position(5, "AAPL", 10));
    when(positionRepository.findByAccountId(5)).thenReturn(book);

    assertThat(positionService.getPositionsByAccountID(5)).isEqualTo(book);
    // The load-bearing part: it must be the account-scoped read, not findAll().
    verify(positionRepository).findByAccountId(5);
  }

  @Test
  void getAllPositions_delegatesToFindAll() {
    List<Position> all = List.of(position(1, "AAPL", 10), position(2, "IBM", -5));
    when(positionRepository.findAll()).thenReturn(all);

    assertThat(positionService.getAllPositions()).isEqualTo(all);
  }
}
