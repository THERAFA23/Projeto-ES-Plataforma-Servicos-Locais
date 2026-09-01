package br.com.ufape.backend.service;

import br.com.ufape.backend.dto.OrcamentoRequestDto;
import br.com.ufape.backend.dto.OrcamentoResponderRequestDto;
import br.com.ufape.backend.dto.OrcamentoResponseDto;
import br.com.ufape.backend.enums.StatusServico;
import br.com.ufape.backend.model.Orcamento;
import br.com.ufape.backend.model.ProviderProfile;
import br.com.ufape.backend.model.Servico;
import br.com.ufape.backend.model.User;
import br.com.ufape.backend.repository.OrcamentoRepository;
import br.com.ufape.backend.repository.ServicoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class OrcamentoService {
    private static final String ERRO_ORCAMENTO_NAO_ENCONTRADO = "Orçamento não encontrado";
    private static final String STATUS_RESPONDIDO = "RESPONDIDO";

    private final OrcamentoRepository orcamentoRepository;
    private final ServicoRepository servicoRepository;

    public OrcamentoService(OrcamentoRepository orcamentoRepository, ServicoRepository servicoRepository) {
        this.orcamentoRepository = orcamentoRepository;
        this.servicoRepository = servicoRepository;
    }

    public OrcamentoResponseDto solicitar(User usuarioAutenticado, OrcamentoRequestDto dto) {
        Servico servico = servicoRepository.findById(dto.servicoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Serviço não encontrado"));

        ProviderProfile prestador = servico.getPrestador();
        if (prestador == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prestador não encontrado");
        }

        Orcamento orcamento = new Orcamento();
        orcamento.setDescricaoNecessidade(dto.descricaoNecessidade());
        orcamento.setLocalAtendimento(dto.localAtendimento());
        orcamento.setDataOuPeriodoDesejado(dto.dataOuPeriodoDesejado());
        orcamento.setServico(servico);
        orcamento.setPrestador(prestador);
        orcamento.setSolicitante(usuarioAutenticado);

        return toResponseDto(orcamentoRepository.save(orcamento));
    }

    public List<OrcamentoResponseDto> buscarRecebidosPorPrestador(Long usuarioId) {
        return orcamentoRepository.findByPrestadorUserId(usuarioId).stream()
                .map(this::toResponseDto)
                .toList();
    }

    public List<OrcamentoResponseDto> buscarSolicitadosPorCliente(Long usuarioId) {
    return orcamentoRepository.findBySolicitanteId(usuarioId).stream()
            .map(this::toResponseDto)
            .toList();
    }

    private OrcamentoResponseDto toResponseDto(Orcamento o) {
        return new OrcamentoResponseDto(
                o.getId(),
                o.getDescricaoNecessidade(),
                o.getLocalAtendimento(),
                o.getDataOuPeriodoDesejado(),
                o.getServico().getId(),
                o.getServico().getTitulo(),
                o.getPrestador().getUser().getName(),
                o.getSolicitante().getName(),
                o.getSolicitante().getEmail(),
                o.getDescricaoResposta(),
                o.getStatusResposta(),
                o.getValorResposta()
        );
    }

    @Transactional
    public OrcamentoResponseDto responder(Long orcamentoId, User prestadorAutenticado, OrcamentoResponderRequestDto dto) {
        
        // lanca Erro 404
        Orcamento orcamento = orcamentoRepository.findById(orcamentoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERRO_ORCAMENTO_NAO_ENCONTRADO));

        //  lanca Erro 403
        Long idDonoDoOrcamento = orcamento.getPrestador().getUser().getId();
        if (!idDonoDoOrcamento.equals(prestadorAutenticado.getId())) {  
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para responder a este orçamento");
        }

        // lanca Erro 400
        if (STATUS_RESPONDIDO.equals(orcamento.getStatusResposta())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este orçamento já foi respondido");
        }

        orcamento.setValorResposta(dto.valorResposta());
        orcamento.setDescricaoResposta(dto.descricaoResposta());
        orcamento.setStatusResposta(STATUS_RESPONDIDO);

        Orcamento orcamentoAtualizado = orcamentoRepository.save(orcamento);

        return toResponseDto(orcamentoAtualizado);
    }
    @Transactional
    public OrcamentoResponseDto aceitar(Long orcamentoId, User clienteAutenticado) {
        Orcamento orcamento = orcamentoRepository.findById(orcamentoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERRO_ORCAMENTO_NAO_ENCONTRADO));

        // Garante que apenas o cliente que criou a solicitação possa aceitar
        if (!orcamento.getSolicitante().getId().equals(clienteAutenticado.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para aceitar este orçamento");
        }

        // Só pode aceitar se o prestador já tiver respondido
        if (!STATUS_RESPONDIDO.equals(orcamento.getStatusResposta())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O orçamento precisa estar respondido para ser aceito");
        }

        Servico servico = orcamento.getServico();
        if (servico.getStatus() != StatusServico.DISPONIVEL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este serviço já possui um orçamento aceito");
        }

        orcamento.setStatusResposta("ACEITO");
        servico.setCliente(clienteAutenticado);
        servico.setStatus(StatusServico.CONTRATADO);
        servico.setDataContratacao(LocalDateTime.now(ZoneOffset.UTC));
        Orcamento orcamentoAtualizado = orcamentoRepository.save(orcamento);

        return toResponseDto(orcamentoAtualizado);
    }

    @Transactional
    public OrcamentoResponseDto recusar(Long orcamentoId, User clienteAutenticado) {
        Orcamento orcamento = orcamentoRepository.findById(orcamentoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERRO_ORCAMENTO_NAO_ENCONTRADO));

        if (!orcamento.getSolicitante().getId().equals(clienteAutenticado.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para recusar este orçamento");
        }

        if (!STATUS_RESPONDIDO.equals(orcamento.getStatusResposta())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O orçamento precisa estar respondido para ser recusado");
        }

        orcamento.setStatusResposta("RECUSADO");
        Orcamento orcamentoAtualizado = orcamentoRepository.save(orcamento);

        return toResponseDto(orcamentoAtualizado);
    }
}
