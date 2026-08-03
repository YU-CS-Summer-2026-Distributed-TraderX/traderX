package finos.traderx.accountservice.service;

import finos.traderx.accountservice.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/*
 * LAYER OVERRIDE, and it has to be one. The composed account-service does not take this file from
 * templates/ -- specs/004-containerized-compose-runtime's 0001-state-overlay.patch supplies it, so
 * a fix to the template alone reaches the BASELINE service and never the deployed one. Editing that
 * patch would put every state from 004 onward at risk of a rendering failure for a two-line change
 * (the same reasoning that kept trade-service untested for months), so the fix lands here instead,
 * where the overlay is last-wins and nothing else has to move.
 *
 * The template copy is fixed too: the baseline service runs its own suite in CI and there is no
 * reason to leave the defect standing there.
 */
@Service
public class PeopleValidationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PeopleValidationService.class);

  private final RestTemplate restTemplate = new RestTemplate();
  private final String peopleServiceAddress;

  public PeopleValidationService(
      @Value("${people.service.url}") String peopleServiceAddress
  ) {
    this.peopleServiceAddress = peopleServiceAddress;
  }

  /**
   * Two things here are deliberate and were both defects until 2026-08-02.
   *
   * <p>The username goes in as a URI VARIABLE, not string concatenation. Concatenated, a username
   * carrying a URL delimiter changed the request rather than being carried by it — "rick&admin=true"
   * arrived at people-service as a second query parameter. RestTemplate encodes expanded variables,
   * so it now arrives as one opaque value whatever it contains.
   *
   * <p>Only a 404 means "no such person". Every other 4xx now propagates, exactly as 5xx already
   * did. Collapsing them reported an expired credential or a policy change to the caller as a
   * missing user — a diagnosis that sends the investigation to the wrong team and hides a fault
   * behind a routine-looking answer.
   */
  public boolean validatePerson(String username) {
    String url = peopleServiceAddress + "/People/GetPerson?LogonId={logonId}";
    try {
      ResponseEntity<Person> response = restTemplate.getForEntity(url, Person.class, username);
      LOGGER.info("Validated person {}", response.getBody());
      return true;
    } catch (HttpClientErrorException.NotFound ex) {
      LOGGER.info("{} not found in people-service", username);
      return false;
    }
  }
}
