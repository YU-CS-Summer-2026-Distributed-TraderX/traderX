import { Controller, Get, NotFoundException, Param } from '@nestjs/common';
import { ControlSnapshot } from '../stocks/control-snapshot';
import { Instrument } from './instrument.model';
import { InstrumentsService } from './instruments.service';

@Controller('instruments')
export class InstrumentsController {
  constructor(private readonly instrumentsService: InstrumentsService) {}

  @Get()
  async findAll(): Promise<Instrument[]> {
    return this.instrumentsService.findAll();
  }

  /**
   * YU16 (FR-CDM11, ADR-058): the general-name control snapshot — identical contract, same
   * store, same watermark as `/stocks/control-snapshot`, which keeps serving unchanged.
   * Declared BEFORE the :instrumentKey route — Nest matches in declaration order.
   */
  @Get('control-snapshot')
  async getControlSnapshot(): Promise<ControlSnapshot> {
    return this.instrumentsService.snapshot();
  }

  @Get(':instrumentKey')
  async findByInstrumentKey(@Param('instrumentKey') instrumentKey: string): Promise<Instrument> {
    const instrument = await this.instrumentsService.findByInstrumentKey(instrumentKey);
    if (!instrument) {
      throw new NotFoundException(`Instrument "${instrumentKey}" not found.`);
    }
    return instrument;
  }
}
