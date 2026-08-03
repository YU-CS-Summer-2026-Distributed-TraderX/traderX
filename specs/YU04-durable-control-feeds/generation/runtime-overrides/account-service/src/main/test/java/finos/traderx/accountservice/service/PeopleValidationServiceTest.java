package finos.traderx.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The people-service lookup behind user assignment.
 *
 * <p>Two distinctions this pins, both of which decide what an operator is told during an incident:
 * whether "the directory said no" and "the directory could not answer" are the same answer, and
 * whether the username reaches the directory intact.
 */
class PeopleValidationServiceTest {

  private static final String PEOPLE = "http://people-service:18089";

  private PeopleValidationService service;
  private MockRestServiceServer http;

  @BeforeEach
  void setUp() {
    service = new PeopleValidationService(PEOPLE);
    RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
    http = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @Test
  void aKnownPersonValidates() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=rick"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"logonId\":\"rick\"}", MediaType.APPLICATION_JSON));

    assertThat(service.validatePerson("rick")).isTrue();
    http.verify();
  }

  @Test
  void anUnknownPersonDoesNotValidate() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=nobody"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(service.validatePerson("nobody")).isFalse();
    http.verify();
  }

  /**
   * A directory OUTAGE is surfaced, not reported as "no such person". The catch covers
   * {@code HttpClientErrorException}, which is 4xx only, so a 5xx propagates — an operator sees the
   * platform is unwell rather than being told a valid user does not exist.
   */
  @Test
  void aPeopleServiceOutageIsSurfacedRatherThanReadAsUnknownUser() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=rick"))
        .andRespond(withServerError());

    assertThatThrownBy(() -> service.validatePerson("rick"))
        .isInstanceOf(HttpServerErrorException.class);
    http.verify();
  }

  /**
   * Only 404 means "no such person". Any other 4xx now propagates, exactly as a 5xx does — an
   * expired credential or a policy change is a fault, not a missing user, and reporting it as one
   * sent the investigation to the wrong team. Fixed 2026-08-02; this test previously asserted the
   * opposite and was written to fail when the fix landed.
   */
  @Test
  void aNon404ClientErrorIsSurfacedRatherThanReadAsUnknownUser() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=rick"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> service.validatePerson("rick"))
        .isInstanceOf(HttpClientErrorException.class);
    http.verify();
  }

  /**
   * The username is carried as a URI variable, so a value containing a URL delimiter is ENCODED
   * rather than changing the request. Before the fix, "rick&admin=true" arrived at people-service
   * as a second query parameter; it now arrives as one opaque LogonId value.
   */
  @Test
  void aUsernameCarryingAQueryDelimiterIsEncodedRatherThanInjected() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=rick%26admin%3Dtrue"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(service.validatePerson("rick&admin=true")).isTrue();
    http.verify();
  }

  /** A space is encoded too, rather than producing an illegal request line. */
  @Test
  void aUsernameContainingASpaceIsEncoded() {
    http.expect(requestTo(PEOPLE + "/People/GetPerson?LogonId=ann%20smith"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(service.validatePerson("ann smith")).isTrue();
    http.verify();
  }
}
