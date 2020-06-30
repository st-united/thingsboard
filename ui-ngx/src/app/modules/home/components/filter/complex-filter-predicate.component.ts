///
/// Copyright © 2016-2020 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component, forwardRef, Input, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { ComplexFilterPredicate, EntityKeyValueType } from '@shared/models/query/query.models';
import { MatDialog } from '@angular/material/dialog';
import {
  ComplexFilterPredicateDialogComponent,
  ComplexFilterPredicateDialogData
} from '@home/components/filter/complex-filter-predicate-dialog.component';
import { deepClone } from '@core/utils';

@Component({
  selector: 'tb-complex-filter-predicate',
  templateUrl: './complex-filter-predicate.component.html',
  styleUrls: [],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ComplexFilterPredicateComponent),
      multi: true
    }
  ]
})
export class ComplexFilterPredicateComponent implements ControlValueAccessor, OnInit {

  @Input() disabled: boolean;

  @Input() userMode: boolean;

  @Input() valueType: EntityKeyValueType;

  private propagateChange = null;

  private complexFilterPredicate: ComplexFilterPredicate;

  constructor(private dialog: MatDialog) {
  }

  ngOnInit(): void {
  }

  registerOnChange(fn: any): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState?(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  writeValue(predicate: ComplexFilterPredicate): void {
    this.complexFilterPredicate = predicate;
  }

  private openComplexFilterDialog() {
    this.dialog.open<ComplexFilterPredicateDialogComponent, ComplexFilterPredicateDialogData,
      ComplexFilterPredicate>(ComplexFilterPredicateDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {
        complexPredicate: deepClone(this.complexFilterPredicate),
        disabled: this.disabled,
        userMode: this.userMode,
        valueType: this.valueType
      }
    }).afterClosed().subscribe(
      (result) => {
        if (result) {
          this.complexFilterPredicate = result;
          this.updateModel();
        }
      }
    );
  }

  private updateModel() {
    this.propagateChange(this.complexFilterPredicate);
  }

}
