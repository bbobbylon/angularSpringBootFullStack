import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InvoiceComponentTs } from './invoice.component.ts';

describe('InvoiceComponentTs', () => {
  let component: InvoiceComponentTs;
  let fixture: ComponentFixture<InvoiceComponentTs>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InvoiceComponentTs],
    }).compileComponents();

    fixture = TestBed.createComponent(InvoiceComponentTs);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
