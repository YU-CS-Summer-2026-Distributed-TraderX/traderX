import { Component, input } from '@angular/core';

/** A small "?" that reveals an explanation on hover — for readers who don't know the system. */
@Component({
  selector: 'help-tip',
  template: `
    <span class="q" tabindex="0">?
      <span class="pop">{{ text() }}</span>
    </span>
  `,
  styles: `
    /* line-height:1 and flex-shrink:0 keep the glyph centred in its circle: inherited line-height
       pushes it off-centre, and a flex parent otherwise squashes the circle into an oval. */
    .q { position: relative; display: inline-flex; align-items: center; justify-content: center;
         width: 15px; height: 15px; flex: 0 0 15px; border-radius: 50%; background: #e8ebef;
         color: #64707f; font-size: 10.5px; font-weight: 600; line-height: 1; cursor: help;
         user-select: none; vertical-align: middle; }
    .pop { display: none; position: absolute; top: 20px; left: -8px; z-index: 30; width: 300px;
           background: #1b2430; color: #f0f3f7; font-size: 12px; font-weight: 400; line-height: 1.5;
           padding: 9px 11px; border-radius: 8px; box-shadow: 0 4px 16px rgba(16, 24, 40, .18);
           white-space: normal; }
    .q:hover .pop, .q:focus .pop { display: block; }
  `,
})
export class HelpTip {
  readonly text = input.required<string>();
}
