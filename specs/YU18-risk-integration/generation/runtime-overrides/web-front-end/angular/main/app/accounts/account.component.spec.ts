import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { AgGridModule } from 'ag-grid-angular';
import { of, throwError } from 'rxjs';
import { AccountComponent } from './account.component';
import { AccountService } from '../service/account.service';
import { UserService } from '../service/user.service';

const ACCOUNTS = [{ id: 22214, displayName: 'Test Account 20' }, { id: 42422, displayName: 'Algo' }];

describe('Account directory and selection', () => {
  let fixture: ComponentFixture<AccountComponent>;
  let component: AccountComponent;
  let directory: jasmine.SpyObj<AccountService>;
  let people: jasmine.SpyObj<UserService>;

  beforeEach(async () => {
    directory = jasmine.createSpyObj('AccountService', ['getAccounts', 'getAccountUsers']);
    directory.getAccounts.and.returnValue(of(ACCOUNTS));
    directory.getAccountUsers.and.returnValue(of([
      { accountId: 22214, username: 'alice' }, { accountId: 22214, username: 'bob' },
      { accountId: 42422, username: 'foreign' }
    ]));
    people = jasmine.createSpyObj('UserService', ['getUser']);
    people.getUser.and.callFake((name) => name === 'bob' ? throwError(() => Error('offline')) : of({ fullName: 'Alice' } as any));
    await TestBed.configureTestingModule({
      declarations: [AccountComponent], imports: [CommonModule, AgGridModule],
      providers: [{ provide: AccountService, useValue: directory }, { provide: UserService, useValue: people }],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(AccountComponent);
    component = fixture.componentInstance;
  });

  it('renders real account rows after the directory debounce', fakeAsync(() => {
    expect(directory.getAccounts).not.toHaveBeenCalled();
    fixture.detectChanges(); tick(250); fixture.detectChanges(); tick(100);
    expect(directory.getAccounts).toHaveBeenCalled();
    const rows = fixture.nativeElement.querySelectorAll('#accountgrid .ag-center-cols-container .ag-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Test Account 20');
    expect((component as any).gridApi).toBeDefined();
    fixture.destroy();
  }));

  it('filters user mappings to the selected account and falls back to username on lookup failure', fakeAsync(() => {
    component.ngOnInit();
    let users: any[] = [];
    const subscription = component.users$.subscribe((value) => users = value);
    component.onUpdate(ACCOUNTS[0]); tick(250);
    expect(users.map((user) => [user.username, user.fullName])).toEqual([['alice', 'Alice'], ['bob', 'bob']]);
    expect(people.getUser).not.toHaveBeenCalledWith('foreign');
    subscription.unsubscribe();
  }));

  it('reports an empty mapping without querying people', fakeAsync(() => {
    component.ngOnInit();
    let users: any[] | undefined;
    const subscription = component.users$.subscribe((value) => users = value);
    tick(250);
    expect(users).toEqual([]);
    expect(people.getUser).not.toHaveBeenCalled();
    subscription.unsubscribe();
  }));

  it('selects a grid account and publishes its identity', () => {
    component.onGridReady({ api: { getSelectedRows: () => [ACCOUNTS[0]] } } as any);
    component.onSelectionChanged();
    expect(component.selectedAccount).toEqual(ACCOUNTS[0]);
    expect(component.accountBehaviorSubject.value).toBe(22214);
  });

  it('an empty grid selection does not change the current account', () => {
    component.onUpdate(ACCOUNTS[0]);
    component.onGridReady({ api: { getSelectedRows: () => [] } } as any);
    component.onSelectionChanged();
    expect(component.selectedAccount).toEqual(ACCOUNTS[0]);
    expect(component.accountBehaviorSubject.value).toBe(22214);
  });

  it('the update callback selects the account and the update action targets the same row', () => {
    component.onUpdate(ACCOUNTS[1]);
    expect(component.selectedAccount).toEqual(ACCOUNTS[1]);
    expect(component.accountBehaviorSubject.value).toBe(42422);
    (component.columnDefs[2].cellRendererParams as any).clicked(ACCOUNTS[0]);
    expect(component.accountToBeUpdate).toEqual(ACCOUNTS[0]);
  });
});
