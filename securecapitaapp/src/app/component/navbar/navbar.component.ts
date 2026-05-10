import { Component, inject, Input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { UserService } from '../../service/user.service';
import { UserInterface } from '../../interface/user.interface';

/**
 * Top navigation bar component.
 *
 * Receives the authenticated user via @Input from the parent and displays
 * their name and avatar. Provides a logout action that clears tokens and
 * navigates to the login screen.
 */
@Component({
  selector: 'app-navbar',
  imports: [RouterLink],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.css',
})
export class NavbarComponent {
  /** The authenticated user passed down from the parent; controls avatar and name display. */
  @Input() user: UserInterface;
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
