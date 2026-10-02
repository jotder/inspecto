import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { LaAppComponent } from './app/la-app.component';

bootstrapApplication(LaAppComponent, appConfig).catch((err) => console.error(err));
