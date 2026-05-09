import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { UserService } from '../../service/user.service';

/** Minimal user shape the navbar needs to render the avatar and greeting. */
interface NavUser {
  firstName: string;
  lastName: string;
  imageUrl: string;
}

/**
 * Top navigation bar component.
 *
 * Fetches the authenticated user's profile on init and displays their name
 * and avatar. Falls back to a Gravatar default if no imageUrl is set.
 * Provides a logout action that clears tokens and reloads the page.
 */
@Component({
  selector: 'app-navbar',
  imports: [RouterLink],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.css',
})
export class NavbarComponent implements OnInit {
  /** The currently authenticated user's display data, or null before load / on error. */
  protected readonly user = signal<NavUser | null>(null);
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);

  /**
   * Loads the current user's profile and populates the user signal.
   * On error (e.g. 401 after token expiry) the signal is set to null
   * so the template can hide user-specific elements gracefully.
   */
  ngOnInit(): void {
    this.userService.profile$().subscribe({
      next: response => {
        const u = response.data?.user;
        if (u) {
          this.user.set({
            firstName: u.firstName,
            lastName: u.lastName,
            imageUrl: u.imageUrl ?? 'https://www.gravatar.com/avatar/?d=mp',
          });
        }
      },
      error: () => this.user.set(null),
    });
  }

  /**
   * Clears both JWT tokens from localStorage and reloads the page,
   * effectively ending the user's session and redirecting to the login screen.
   */
  protected logOut(): void {
    this.userService.logOut();
    this.router.navigate(['/login']);
  }
}
