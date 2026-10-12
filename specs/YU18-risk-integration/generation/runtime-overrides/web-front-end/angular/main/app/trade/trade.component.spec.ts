import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { AlertModule } from 'ngx-bootstrap/alert';
import { BsModalService } from 'ngx-bootstrap/modal';
import { of, Subject, BehaviorSubject } from 'rxjs';
import { OrderAdminService } from '../service/order-admin.service';
import { AlgoService } from '../service/algo.service';
import { PositionService } from '../service/position.service';
import { TradeFeedService } from '../service/trade-feed.service';
import { Fdc3InteropService } from '../service/fdc3-interop.service';
import { TradeComponent } from './trade.component';
import { AccountService } from '../service/account.service';
import { SymbolService } from '../service/symbols.service';
import { DropdownModule } from '../dropdown/dropdown.module';
import { Side } from '../model/trade.model';

const ACCOUNTS = [{ id: 22214, displayName: 'Test Account 20' }, { id: 42422, displayName: 'Algo' }];
const TICKET = { accountId: 22214, quantity: 10, security: 'IBM', side: Side.Buy };

describe('Trade account and ticket controls', () => {
  let fixture: ComponentFixture<TradeComponent>;
  let component: TradeComponent;
  let accounts: any;
  let symbols: any;
  let modal: any;
  let hide: jasmine.Spy;

  beforeEach(async () => {
    accounts = { getAccounts: jasmine.createSpy('getAccounts').and.returnValue(of(ACCOUNTS)) };
    symbols = { getStocks: jasmine.createSpy('getStocks').and.returnValue(of([{ ticker: 'IBM', companyName: 'IBM' }])),
      createTicket: jasmine.createSpy('createTicket').and.returnValue(of({ success: true })) };
    hide = jasmine.createSpy('hide'); modal = { show: jasmine.createSpy('show').and.returnValue({ hide }) };
    await TestBed.configureTestingModule({ declarations: [TradeComponent],
      imports: [CommonModule, FormsModule, DropdownModule, AlertModule.forRoot()],
      providers: [{ provide: AccountService, useValue: accounts }, { provide: SymbolService, useValue: symbols },
        { provide: BsModalService, useValue: modal },
        { provide: OrderAdminService, useValue: {} }, { provide: AlgoService, useValue: {} },
        { provide: PositionService, useValue: { getAllPositions: () => of([]) } },
        { provide: TradeFeedService, useValue: { subscribe: () => () => {} } },
        { provide: Fdc3InteropService, useValue: { inboundEvents$: new Subject(),
          isAgentAvailable$: new BehaviorSubject(false), statusMessage$: new BehaviorSubject('offline'),
          initialize: () => Promise.resolve(false), publishInstrument: () => Promise.resolve(false) } }], schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(TradeComponent); component = fixture.componentInstance;
  });

  it('loads the directory with a distinct all-account choice and defaults to a real account', () => {
    expect(accounts.getAccounts).not.toHaveBeenCalled(); fixture.detectChanges();
    expect(accounts.getAccounts).toHaveBeenCalledTimes(1); expect(symbols.getStocks).toHaveBeenCalledTimes(1);
    expect(component.accounts.map((account) => account.id)).toEqual([0, 22214, 42422]);
    expect(component.accountModel).toEqual(ACCOUNTS[0]); expect(component.stocks[0].ticker).toBe('IBM');
    expect(fixture.nativeElement.querySelector('#createTicketBtn')).not.toBeNull();
  });

  it('submits an individual ticket and closes its open modal', () => {
    fixture.detectChanges(); component.openTicket({} as any); component.createTradeTicket(TICKET);
    expect(symbols.createTicket).toHaveBeenCalledWith(TICKET); expect(component.createTicketResponse).toEqual({ success: true });
    expect(hide).toHaveBeenCalledTimes(1);
  });

  it('all-account selection refuses a ticket and does not open its modal', () => {
    fixture.detectChanges(); component.onAccountChange(component.accounts[0]);
    component.openTicket({} as any); component.createTradeTicket(TICKET);
    expect(modal.show).not.toHaveBeenCalled(); expect(symbols.createTicket).not.toHaveBeenCalled();
    expect(component.createTicketResponse.success).toBeFalse();
  });

  it('individual selection opens a modal and close hides it', () => {
    fixture.detectChanges(); component.onAccountChange(ACCOUNTS[1]); component.openTicket({} as any); component.closeTicket();
    expect(component.accountModel?.id).toBe(42422); expect(modal.show).toHaveBeenCalledTimes(1); expect(hide).toHaveBeenCalledTimes(1);
  });

  it('an empty directory selects all accounts and refuses individual trading', () => {
    accounts.getAccounts.and.returnValue(of([])); fixture.detectChanges(); component.createTradeTicket(TICKET);
    expect(component.accounts.map((account) => account.id)).toEqual([0]); expect(component.accountModel?.id).toBe(0);
    expect(symbols.createTicket).not.toHaveBeenCalled(); expect(component.createTicketResponse.success).toBeFalse();
  });

  it('clears an alert and retains the selected account when a selection is absent', () => {
    fixture.detectChanges(); component.createTicketResponse = { success: true }; component.onCloseAlert();
    expect(component.createTicketResponse).toBeUndefined(); component.onAccountChange(undefined as any);
    expect(component.accountModel).toEqual(ACCOUNTS[0]); expect(component.getAccountName(ACCOUNTS[0])).toBe('Test Account 20');
  });
});
