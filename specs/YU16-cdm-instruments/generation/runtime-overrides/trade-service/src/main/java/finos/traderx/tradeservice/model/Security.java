package finos.traderx.tradeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * YU16 (FR-CDM01/02): the CDM instrument record as trade-service consumes it from
 * {@code GET /instruments/{instrumentKey}}. Unknown fields are ignored so reference-data can
 * extend the record without moving this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Security {
  private String instrumentKey;
  private String displayName;
  private String assetClass;
  private String securityType;
  private boolean matured;
  private DebtEconomics debtEconomics;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DebtEconomics {
    private String maturityDate;

    public String getMaturityDate() {
      return maturityDate;
    }

    public void setMaturityDate(String maturityDate) {
      this.maturityDate = maturityDate;
    }
  }

  public Security() {}

  /** The {@code UST-} prefix routes; this record authorizes (FR-CDM23). */
  public boolean isTreasury() {
    return "US_TREASURY".equals(assetClass) && "Debt".equals(securityType);
  }

  public String getInstrumentKey() {
    return instrumentKey;
  }

  public void setInstrumentKey(String instrumentKey) {
    this.instrumentKey = instrumentKey;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getAssetClass() {
    return assetClass;
  }

  public void setAssetClass(String assetClass) {
    this.assetClass = assetClass;
  }

  public String getSecurityType() {
    return securityType;
  }

  public void setSecurityType(String securityType) {
    this.securityType = securityType;
  }

  public boolean isMatured() {
    return matured;
  }

  public void setMatured(boolean matured) {
    this.matured = matured;
  }

  public DebtEconomics getDebtEconomics() {
    return debtEconomics;
  }

  public void setDebtEconomics(DebtEconomics debtEconomics) {
    this.debtEconomics = debtEconomics;
  }
}
