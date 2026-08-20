import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';

import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from './page-size-select.component';

/**
 * Specs for {@link PageSizeSelectComponent} — the "Rows per page" control shared by every pager
 * in the application.
 *
 * <h3>What is actually worth asserting here</h3>
 * The component is nine lines of template, so these specs are not about its rendering. They pin the
 * two properties that the seven screens consuming it silently depend on:
 *
 * <ul>
 *   <li><b>It emits a {@code number}.</b> The DOM reports {@code <select>} values as strings, and
 *       every caller feeds the emitted value straight into a query string or an array
 *       {@code slice()}. A leaked {@code "50"} would sail through TypeScript (the output is typed,
 *       but the conversion happens at a {@code $any()} boundary in the template) and then produce
 *       {@code ?size=50} that happens to work, alongside a {@code slice(start, start + "50")} that
 *       silently returns nothing. Asserting the runtime type is the only way to catch it.</li>
 *   <li><b>Re-picking the current size emits nothing.</b> Every handler wired to this control
 *       resets the page index and — on four of the seven screens — issues an HTTP request. A
 *       no-op selection that still fired would throw away the reader's position for no change.</li>
 * </ul>
 *
 * <p>The app is zoneless, so each interaction is followed by an explicit
 * {@code fixture.detectChanges()}.
 *
 * <p>{@link TranslocoTestingModule} supplies a one-key dictionary rather than the real
 * {@code en.json}: the label's wording is not what these specs are about, and importing the live
 * file would make them fail on a copy edit.
 */
describe('PageSizeSelectComponent', () => {
  let fixture: ComponentFixture<PageSizeSelectComponent>;
  let emitted: number[];

  /** The host element. {@code fixture.nativeElement} is `any`, so narrow it once here. */
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const select = (): HTMLSelectElement => host().querySelector('select') as HTMLSelectElement;

  /** The offered values, as rendered. */
  const renderedOptions = (): string[] =>
    Array.from(host().querySelectorAll('option')).map((option) => option.value);

  /** Picks a value the way a user does — set, dispatch, flush. */
  const choose = (value: string): void => {
    const element = select();
    element.value = value;
    element.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        PageSizeSelectComponent,
        TranslocoTestingModule.forRoot({
          langs: { en: { common: { rowsPerPage: 'Rows per page:' } } },
          translocoConfig: { availableLangs: ['en'], defaultLang: 'en' },
        }),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PageSizeSelectComponent);
    emitted = [];
    fixture.componentRef.setInput('size', 20);
    fixture.componentInstance.sizeSelected.subscribe((size) => emitted.push(size));
    fixture.detectChanges();
  });

  it('offers the shared option list', () => {
    expect(renderedOptions()).toEqual(PAGE_SIZE_OPTIONS.map(String));
  });

  it('marks the size currently in effect as selected', () => {
    expect(select().value).toBe('20');
  });

  it('emits the chosen size as a number, not the string the DOM reports', () => {
    choose('50');

    expect(emitted).toEqual([50]);
    // toEqual would pass for '50' on a loose comparison in some matchers; be explicit, because a
    // string here breaks slice() arithmetic on the client-side pagers without throwing.
    expect(typeof emitted[0]).toBe('number');
  });

  it('emits nothing when the size already in effect is re-selected', () => {
    choose('20');

    expect(emitted).toEqual([]);
  });

  it('reflects a size changed by its parent', () => {
    fixture.componentRef.setInput('size', 100);
    fixture.detectChanges();

    expect(select().value).toBe('100');
  });

  it('honors a caller-supplied option list', () => {
    fixture.componentRef.setInput('options', [5, 25]);
    fixture.detectChanges();

    expect(renderedOptions()).toEqual(['5', '25']);
  });
});
