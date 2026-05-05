// In this class, we must map the variables properly and use the same names as in the backend otherwise, we will get an error when we try to use the data from the backend. We must also make sure that the types of the variables are correct, otherwise we will get an error when we try to use the data from the backend. We must also make sure that we import this interface in the components where we want to use it.
export interface User {
  id: number;
  username: string;
  email: string;
  phoneNumber: string;
  firstName?: string;
  lastName?: string;
  address?: string;
  title?: string;
  bio?: string;
  imageUrl?: string;
  enabled: boolean;
  isNotLocked: boolean;
  using2FA: boolean;
  createdAt: Date;
  roleName: string;
  permissions: string;
}
