import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import { TRANSLOCO_TESTING_IMPORTS } from '../../testing/transloco-testing';
import { FieldErrorComponent } from './field-error.component';

/**
 * Specs for {@link FieldErrorComponent} — the shared "why is this field invalid" message
 * (FUTURE-ENHANCEMENTS.md §3.7). Driven through a real {@code ngModel}-bound input rather than a
 * mocked {@code NgModel}, since the component's whole contract is reading the live
 * `invalid`/`touched`/`dirty`/`errors` state that only the real forms module produces.
 *
 * <p>{@code TRANSLOCO_TESTING_IMPORTS} resolves every key to itself (no {@code en.json} loaded),
 * so assertions check for the i18n key rather than translated copy — see that helper's doc.
 */
describe('FieldErrorComponent', () => {
  @Component({
    standalone: true,
    imports: [FormsModule, FieldErrorComponent],
    template: `
      <input #field="ngModel" [(ngModel)]="value" name="field" required minlength="3" pattern="[0-9]+" />
      <app-field-error [control]="field" />
    `,
  })
  class HostComponent {
    value = '';
  }

  let fixture: ComponentFixture<HostComponent>;
  const input = (): HTMLInputElement => fixture.nativeElement.querySelector('input');
  const message = (): string | null =>
    fixture.nativeElement.querySelector('.invalid-feedback')?.textContent?.trim() ?? null;

  const mount = (): void => {
    TestBed.configureTestingModule({ imports: [HostComponent, ...TRANSLOCO_TESTING_IMPORTS] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  };

  const setValue = (value: string): void => {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  const blur = (): void => {
    input().dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  };

  it('shows nothing before the user has touched or edited the field, even though it starts invalid', () => {
    mount();

    expect(message()).toBeNull();
  });

  it('shows the required message once an empty field is blurred', () => {
    mount();

    blur();

    expect(message()).toContain('validation.required');
  });

  it('shows a message as soon as the field is edited, without waiting for blur', () => {
    mount();

    setValue('a');

    expect(message()).not.toBeNull();
  });

  it('picks one message when multiple validators fail, in priority order (pattern before minlength)', () => {
    mount();

    // Length 1 fails both minlength=3 and pattern=[0-9]+ — pattern outranks minlength.
    setValue('a');

    expect(message()).toContain('validation.pattern');
  });

  it('falls through to the next validator once the higher-priority one is satisfied', () => {
    mount();

    // Matches the digits-only pattern but is still one character short of minlength=3.
    setValue('1');

    expect(message()).toContain('validation.minlength');
  });

  it('clears the message once the field becomes valid', () => {
    mount();
    blur();
    expect(message()).not.toBeNull();

    setValue('123');

    expect(message()).toBeNull();
  });
});
