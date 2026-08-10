import { Injectable } from '@angular/core';
import { Stock } from '../model/symbol.model';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map, retry } from 'rxjs/operators';
import { TradeTicket } from '../model/trade.model';
import { environment } from 'main/environments/environment';

// The CDM instrument record as served by GET /instruments (YU16, FR-CDM01).
interface InstrumentRecord {
    instrumentKey: string;
    displayName: string;
    shortDisplayName?: string;
    assetClass?: 'Stock' | 'ETF' | 'US_TREASURY';
    securityType?: string;
    matured?: boolean;
    debtEconomics?: {
        fixedInterest?: { couponRatePercent?: number };
        maturityDate?: string;
    };
}

@Injectable({
    providedIn: 'root'
})
export class SymbolService {
    // YU16 (source FR-01614/TD-01603 carried as TD-CDM01): the endpoint moves to the CDM view;
    // the service and method keep their historic names, and the record maps back into the
    // historic Stock shape with the CDM attributes riding along as optional fields.
    private instrumentsUrl = `${environment.refrenceDataUrl}/instruments`;
    private createTicketUrl = `${environment.tradesUrl}`;
    constructor(private http: HttpClient) { }

    getStocks(): Observable<Stock[]> {
        return this.http.get<InstrumentRecord[]>(this.instrumentsUrl).pipe(
            retry(2),
            map((instruments) => (instruments || []).map((instrument) => this.toStock(instrument))),
            catchError(this.handleError)
        );
    }

    createTicket(ticket: TradeTicket): Observable<any> {
        return this.http.post(this.createTicketUrl, ticket).pipe(
            catchError(this.handleError)
        );
    }

    private toStock(instrument: InstrumentRecord): Stock {
        return {
            ticker: instrument.instrumentKey,
            companyName: instrument.displayName,
            assetClass: instrument.assetClass,
            securityType: instrument.securityType,
            shortDisplayName: instrument.shortDisplayName,
            matured: instrument.matured,
            debtEconomics: instrument.debtEconomics ? {
                couponRatePercent: instrument.debtEconomics.fixedInterest?.couponRatePercent,
                maturityDate: instrument.debtEconomics.maturityDate
            } : undefined
        };
    }

    private handleError(error: HttpErrorResponse) {
        console.error(error);
        return throwError(() => error);
    }
}
