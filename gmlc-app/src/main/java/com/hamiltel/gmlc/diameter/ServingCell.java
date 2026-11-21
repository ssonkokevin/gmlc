package com.hamiltel.gmlc.diameter;

/**
 * The serving cell a Provide-Location answer reported, in the parts the cell
 * database uses. The area code is the TAC for LTE and the LAC for GSM; ECGI
 * does not carry a TAC, so an LTE cell is resolved by PLMN and cell code alone.
 *
 * @param accuracyFulfilled the MME's own statement about the requested
 *                          accuracy, or null when it said nothing
 * @param ageSeconds        the MME's age of the location estimate, or null
 */
public record ServingCell(
        String rat,
        String mcc,
        String mnc,
        String areaCode,
        String cellCode,
        Boolean accuracyFulfilled,
        Integer ageSeconds) {
}
