import { ChangeDetectionStrategy, Component, inject, Input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { UserService } from '../../service/user.service';
import { UserInterface } from '../../interface/user.interface';
import { NgOptimizedImage } from '@angular/common';

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

  /**
   * Clears both JWT tokens from localStorage and navigates to the login screen,
   * effectively ending the user's session.
   */
  protected logOut(): void {
    this.userService.logOut();
    this.router.navigate(['/login']);
  }
}
