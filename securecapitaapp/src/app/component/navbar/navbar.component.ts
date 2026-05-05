import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

interface NavUser {
  firstName: string;
  lastName: string;
  imageUrl: string;
}

@Component({
  selector: 'app-navbar',
  imports: [RouterLink],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.css',
})
export class NavbarComponent {
  protected readonly user = signal<NavUser | null>({
    firstName: 'John',
    lastName: 'Doe',
    imageUrl: 'https://www.gravatar.com/avatar/?d=mp',
  });

  protected logOut(): void {
    localStorage.removeItem('token');
    localStorage.removeItem('refreshToken');
    window.location.reload();
  }
}
