import { Injectable } from '@angular/core';
import { Observable, map, catchError } from 'rxjs';
import { HttpClient } from '@angular/common/http';

import { AppHttpService } from './modules/commons/app.http.service';
import { environment } from 'environments/environment';

@Injectable({
    providedIn: 'root'
})
export class AppComponentService extends AppHttpService {

    constructor(public httpClient: HttpClient) {
        super(httpClient);
    }

    saveRouterActionEvent(eventPayload: any): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/act-logging/appPageChangeEvent';
        return this.http
            .post(apiUrl, JSON.stringify(eventPayload), this.options).pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    getAppPages(): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/app-setting/getAppPages';
        return this.http.get<any>(apiUrl, this.options)
            .pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    saveNewAppPage(pageMeta: any): Observable<any> {
        const apiUrl = environment.appUrl + environment.apiVersion + '/app-setting/newAppPage';
        return this.http.post(apiUrl, JSON.stringify(pageMeta), this.options)
            .pipe(
                map((response: Response) => {
                    return response;
                }),
                catchError(this.handleError));
    }

    getUserDetails(): Observable<any> {
        const apiUrl = environment.gatewayServerUrl + environment.apiVersion + '/um/getUserDetails';
        // return this.http.get(apiUrl, this.options)

        return this.http.get(apiUrl, this.options).pipe(
            map((response: Response) => {
                return response;
            }),
            catchError(this.handleError));

    }
}

// 'http://68.183.16.242:6601/api/v1/um/getUserDetails'