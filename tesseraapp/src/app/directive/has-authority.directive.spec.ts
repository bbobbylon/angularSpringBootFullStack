import { Component, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { HasAuthorityDirective, RequiresAuthorityDirective } from './has-authority.directive';
import { UserService } from '../service/user.service';

/**
 * Specs for the capability-level RBAC gating directives (ROADMAP §2).
 *
 * <p>These are driven through real host components and rendered DOM rather than by calling the
 * directives' methods, because what matters is the observable outcome — is the control on the
 * page, and can it be activated — not how the directive arrived at it.
 *
 * <p>The negative cases carry the weight here. A gating directive that fails *open* (renders a
 * control it should have withheld) produces a user who is told they can do something and then
 * refused by the server; one that fails *closed* (withholds a control the user is entitled to)
 * silently removes functionality an administrator believes they granted, which is harder to
 * notice and harder to diagnose. Both directions are asserted.
 *
 * <p>Worth restating what is *not* being tested: security. The backend enforces these same
 * authorities on every request (NFR-SEC-4), so a user who defeats these directives gains a
 * rendered button and nothing else. These specs protect the UX guarantee.
 */
describe('capability gating directives', () => {
  let hasAnyAuthority: ReturnType<typeof vi.fn>;

  /**
   * Installs a UserService test double whose authority answers come from a set, then compiles the
   * given host component.
   *
   * @param granted the authorities the signed-in account holds
   * @param host the host component class under test
   */
  const mount = <T>(granted: string[], host: Type<T>): ComponentFixture<T> => {
    const set = new Set(granted);
    hasAnyAuthority = vi.fn((...authorities: string[]) => authorities.some((authority) => set.has(authority)));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [host],
      providers: [{ provide: UserService, useValue: { hasAnyAuthority } }],
    });

    const fixture = TestBed.createComponent(host);
    fixture.detectChanges();
    return fixture;
  };

  const html = (fixture: ComponentFixture<unknown>): HTMLElement => fixture.nativeElement as HTMLElement;

  describe('*appHasAuthority', () => {
    @Component({
      standalone: true,
      imports: [HasAuthorityDirective],
      template: `
        <button *appHasAuthority="'UPDATE:CUSTOMER'" id="save">Save</button>
      `,
    })
    class SingleAuthorityHost {}

    @Component({
      standalone: true,
      imports: [HasAuthorityDirective],
      template: `
        <button *appHasAuthority="['UPDATE:CUSTOMER', 'UPDATE:USER']; else readOnly" id="save">Save</button>
        <ng-template #readOnly><p id="notice">Read-only</p></ng-template>
      `,
    })
    class ElseTemplateHost {}

    @Component({
      standalone: true,
      imports: [HasAuthorityDirective],
      template: `<button *appHasAuthority="'UPDATE:USER, UPDATE:CUSTOMER'" id="save">Save</button>`,
    })
    class CommaSeparatedHost {}

    it('renders the control when the authority is held', () => {
      const fixture = mount(['UPDATE:CUSTOMER'], SingleAuthorityHost);

      expect(html(fixture).querySelector('#save')).not.toBeNull();
    });

    it('removes the control entirely when the authority is missing', () => {
      const fixture = mount(['READ:CUSTOMER'], SingleAuthorityHost);

      // Absent from the DOM, not merely hidden — a control that is only visually hidden is still
      // reachable by keyboard and by assistive technology.
      expect(html(fixture).querySelector('#save')).toBeNull();
    });

    it('treats a list of authorities as OR, matching the backend hasAnyAuthority rule', () => {
      // Holds the second of the two listed authorities only.
      const fixture = mount(['UPDATE:USER'], ElseTemplateHost);

      expect(html(fixture).querySelector('#save')).not.toBeNull();
      expect(html(fixture).querySelector('#notice')).toBeNull();
    });

    it('swaps in the else template instead of leaving a hole', () => {
      const fixture = mount(['READ:CUSTOMER'], ElseTemplateHost);

      expect(html(fixture).querySelector('#save')).toBeNull();
      expect(html(fixture).querySelector('#notice')?.textContent).toContain('Read-only');
    });

    it('accepts a comma-separated string, the form roles are stored in', () => {
      // `roles.permission` in schema.sql is a comma-separated column; a developer pasting one in
      // must not get a silent never-match.
      const fixture = mount(['UPDATE:CUSTOMER'], CommaSeparatedHost);

      expect(html(fixture).querySelector('#save')).not.toBeNull();
      expect(hasAnyAuthority).toHaveBeenCalledWith('UPDATE:USER', 'UPDATE:CUSTOMER');
    });
  });

  describe('[appRequiresAuthority]', () => {
    @Component({
      standalone: true,
      imports: [RequiresAuthorityDirective],
      template: `
        <button [appRequiresAuthority]="'UPDATE:CUSTOMER'" deniedAction="update customers" id="save" type="submit">
          Save
        </button>
      `,
    })
    class ButtonHost {}

    @Component({
      standalone: true,
      imports: [RequiresAuthorityDirective],
      template: `<a [appRequiresAuthority]="'UPDATE:CUSTOMER'" href="/customer/new" id="link">New</a>`,
    })
    class AnchorHost {}

    it('leaves a permitted control completely untouched', () => {
      const fixture = mount(['UPDATE:CUSTOMER'], ButtonHost);
      const button = html(fixture).querySelector('#save') as HTMLButtonElement;

      expect(button.disabled).toBe(false);
      expect(button.getAttribute('aria-disabled')).toBeNull();
      expect(button.getAttribute('title')).toBeNull();
      expect(button.classList.contains('is-restricted')).toBe(false);
    });

    it('disables, labels, and explains a control the user may not use', () => {
      const fixture = mount(['READ:CUSTOMER'], ButtonHost);
      const button = html(fixture).querySelector('#save') as HTMLButtonElement;

      expect(button.disabled).toBe(true);
      expect(button.getAttribute('aria-disabled')).toBe('true');
      expect(button.classList.contains('is-restricted')).toBe(true);
      // The tooltip names the capability and points at the remedy — the same phrasing adminGuard
      // uses at the route level, so a user hits one consistent sentence rather than two.
      expect(button.getAttribute('title')).toBe(
        "You don't have permission to update customers — contact your administrator.",
      );
    });

    it('never reveals anything beyond the caller own permissions', () => {
      const fixture = mount(['READ:CUSTOMER'], ButtonHost);
      const title = (html(fixture).querySelector('#save') as HTMLElement).getAttribute('title') ?? '';

      // Non-enumeration: the message may name a capability, never a record, an account, a role
      // name, or a count of anything that exists.
      expect(title).not.toMatch(/ROLE_|\d+|@/);
    });

    it('removes an anchor from the tab order, since it has no disabled property', () => {
      const fixture = mount(['READ:CUSTOMER'], AnchorHost);
      const link = html(fixture).querySelector('#link') as HTMLAnchorElement;

      // Without this a keyboard user could still Tab to and activate a control that a pointer
      // user cannot reach — the classic way a "disabled" state fails accessibility.
      expect(link.getAttribute('tabindex')).toBe('-1');
      expect(link.getAttribute('aria-disabled')).toBe('true');
    });
  });
});
