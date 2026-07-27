import { ChangeDetectionStrategy, Component, inject, Input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { UserService } from '../../service/user.service';
import { UserInterface } from '../../interface/user.interface';
import { NgOptimizedImage } from '@angular/common';
import { ThemeService } from '../../service/theme.service';

/**
 * Top navigation bar component.
 *
 * Receives the authenticated user via {@code @Input} from the parent and displays
 * their name and avatar. Provides a logout action that clears tokens and
 * navigates to the login screen.
 *
 * TODO: Decouple user data from the customer list response. Currently the parent (HomeComponent)
 *  passes the user down from {@code data.user} inside the {@code GET /customer/list} response.
 *  Instead, inject {@link UserService} here and call {@code userService.profile$()} on init
 *  so the navbar fetches the user independently from {@code GET /user/profile} ({@code data.user}).
 *  This removes the dependency on the customer list endpoint having loaded before the navbar
 *  can display user info, and keeps user identity concerns out of the customer list response.
 *  When making this change: remove {@code @Input() user}, restore {@code ngOnInit},
 *  and remove the {@code [user]} binding from every parent template.
 */
@Component({
  selector: 'app-navbar',
  imports: [RouterLink, NgOptimizedImage],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NavbarComponent {
  /** The authenticated user passed down from the parent; controls avatar and name display. */
  @Input() user: UserInterface | undefined;
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly themeService = inject(ThemeService);

  /**
   * The active color mode, exposed for the toggle button's icon and labels.
   * Reads the shared signal from {@link ThemeService}.
   */
  protected readonly theme = this.themeService.theme;

  /**
   * Whether the navbar should show the administrative menu (SRS FR-ADMIN-5).
   * Evaluated once per navbar instantiation from the access token's authorities claim
   * — the same staff-grade authorities (UPDATE:USER / UPDATE:ROLE) that adminGuard and
   * the backend's /admin/** rules require. Hiding the link is a usability choice only;
   * the route guard and the server-side checks are the real enforcement (NFR-SEC-4).
   */
  protected readonly canManageUsers = this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE');

  /**
   * Whether the current user is a super-admin (DELETE:USER authority = highest privilege).
   *
   * Super-admins see the Billing Overview with system-wide scope and the
   * Role Assignment controls, which go beyond the org-admin tier
   * (UPDATE:USER / UPDATE:ROLE) that can manage users within their organization.
   */
  protected readonly isSuperAdmin = this.userService.hasAnyAuthority('DELETE:USER');

  /**
   * Whether the user may create business records — gates the "New Customer" / "New Invoice"
   * entries (ROADMAP §2, capability-level RBAC gating).
   *
   * Both creation endpoints fall through to SecurityConfig's
   * {@code .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")}, so
   * an account holding only {@code READ:*} — {@code ROLE_USER} and {@code ROLE_GUEST} — can reach
   * both forms, fill them in, and receive a 403 on submit. Menu entries are *hidden* rather than
   * disabled here (unlike the Save button on customer-details): a navigation list is a menu of
   * available destinations, and a permanently dead entry in it is noise, whereas a missing submit
   * button inside a form the user is already looking at reads as a bug.
   *
   * Browsing remains ungated — "All Customers" and "All Invoices" only need {@code READ:CUSTOMER},
   * which every role has.
   */
  protected readonly canCreateRecords = this.userService.hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER');

  /**
   * Flips the application between dark and light mode via {@link ThemeService},
   * which updates the {@code data-bs-theme} attribute and persists the choice.
   */
  protected toggleTheme(): void {
    this.themeService.toggle();
  }

  /**
   * Clears both JWT tokens from localStorage and navigates to the login screen,
   * effectively ending the user's session.
   */
  protected logOut(): void {
    this.userService.logOut();
    this.router.navigate(['/login']);
  }
}
