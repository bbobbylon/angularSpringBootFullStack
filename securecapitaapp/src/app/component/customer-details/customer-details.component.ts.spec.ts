import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CustomerDetailsComponentTs } from './customer-details.component.ts';

describe('CustomerDetailsComponentTs', () => {
  let component: CustomerDetailsComponentTs;
  let fixture: ComponentFixture<CustomerDetailsComponentTs>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CustomerDetailsComponentTs],
    }).compileComponents();

    fixture = TestBed.createComponent(CustomerDetailsComponentTs);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
