package finos.traderx.tradeprocessor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * YU16 (FR-CDM23/24): the slice of reference-data's CDM instrument record booking needs. The
 * {@code UST-} prefix routes an order here, but the prefix alone never authorizes a Treasury
 * booking — this record must confirm {@code US_TREASURY} + {@code Debt}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentMetadata {

  private String instrumentKey;
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

  public boolean isTreasury() {
    return "US_TREASURY".equals(assetClass) && "Debt".equals(securityType);
  }

  /**
   * Any bond — Treasury or corporate. The FACE-AMOUNT rule keys off THIS, not isTreasury():
   * quantity is USD face for every debt instrument, so "at least 100 and a multiple of 100"
   * applies to all of them. Keying it on isTreasury() let a corporate order for 50 face through.
   */
  public boolean isBond() {
    return "Debt".equals(securityType);
  }

  public String getInstrumentKey() {
    return instrumentKey;
  }

  public void setInstrumentKey(String instrumentKey) {
    this.instrumentKey = instrumentKey;
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
