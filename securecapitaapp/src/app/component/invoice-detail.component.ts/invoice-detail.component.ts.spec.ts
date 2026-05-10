import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InvoiceDetailComponentTs } from './invoice-detail.component.ts';

describe('InvoiceDetailComponentTs', () => {
  let component: InvoiceDetailComponentTs;
  let fixture: ComponentFixture<InvoiceDetailComponentTs>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InvoiceDetailComponentTs],
    }).compileComponents();

    fixture = TestBed.createComponent(InvoiceDetailComponentTs);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
